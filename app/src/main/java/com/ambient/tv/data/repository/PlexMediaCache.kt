package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.LibraryHub
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import com.ambient.tv.data.browse.BrowseDiskStore
import com.ambient.tv.data.browse.BrowseSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Process-lifetime stale-while-revalidate cache shared by every route. */
@Singleton
open class PlexMediaCache @Inject constructor() {
    @Volatile private var generation = 0L
    private val requestGeneration = ThreadLocal<Long?>()

    private var disk: BrowseDiskStore? = null
    private var scopeKey: (() -> String)? = null
    @Volatile private var activeScope: String? = null
    @Volatile private var restored = false
    private val restoreLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun configurePersistence(store: BrowseDiskStore, scope: () -> String) {
        disk = store
        scopeKey = scope
        syncScope()
    }

    @Synchronized private fun syncScope() {
        val next = scopeKey?.invoke() ?: return
        if (next == activeScope) return
        val old = activeScope
        clearMemory()
        activeScope = next
        restored = false
        old?.let { disk?.remove(it) }
    }

    suspend fun restore() = withContext(Dispatchers.IO) { restoreInternal() }

    private suspend fun restoreInternal() {
        syncScope()
        if (restored) return
        restoreLock.withLock {
            syncScope()
            if (restored) return@withLock
            val source = activeScope ?: return@withLock
            val expected = generation
            val records = disk?.read(source).orEmpty()
            val decoded = withContext(Dispatchers.IO) {
                records.mapNotNull { (key, value) -> runCatching { key to json.decodeFromString<BrowseSnapshot>(value) }.getOrNull() }
            }
            synchronized(this) {
                syncScope()
                if (expected != generation || source != activeScope) return@synchronized
                for ((key, value) in decoded) {
                    // Disk data is usable immediately, but always revalidated after process start.
                    val timed = TimedValue(value.items, 0L)
                    when {
                        key == "libraries" -> if (librarySections == null) librarySections = value.sections?.let { TimedValue(it, 0L) }
                        key == "ondeck" -> if (onDeck == null) onDeck = timed
                        key.startsWith("section:") -> sectionItems.putIfAbsent(key.removePrefix("section:"), timed)
                        key.startsWith("preview:") -> sectionPreviews.putIfAbsent(key.removePrefix("preview:"), timed)
                        key.startsWith("children:") -> children.putIfAbsent(key.removePrefix("children:"), timed)
                        key.startsWith("similar:") -> similar.putIfAbsent(key.removePrefix("similar:"), timed)
                        key.startsWith("filtered:") -> filtered.putIfAbsent(key.removePrefix("filtered:"), timed)
                        key.startsWith("hubs:") -> value.hubs?.let { hubs.putIfAbsent(key.removePrefix("hubs:"), TimedValue(it, 0L)) }
                    }
                    (value.items + value.hubs.orEmpty().flatMap { it.items }).forEach { item ->
                        item.allRatingKeys.forEach { mergedByRatingKey.putIfAbsent(it, item) }
                    }
                }
                restored = true
            }
        }
    }

    private fun persist(key: String, snapshot: BrowseSnapshot) {
        val source = activeScope ?: return
        val expected = generation
        disk?.put(source, key, { generation == expected && scopeKey?.invoke() == source }) {
            com.ambient.tv.data.browse.encodeBrowseJson(json, snapshot.displayOnly())
        }
    }

    /** A refresh expires data without destroying the last successful offline snapshot. */
    @Synchronized fun expire() {
        syncScope()
        generation++
        librarySections = librarySections?.copy(storedAtMs = 0)
        onDeck = onDeck?.copy(storedAtMs = 0)
        listOf(sectionItems, sectionPreviews, children, similar, filtered, searches).forEach { map ->
            map.replaceAll { _, value -> value.copy(storedAtMs = 0) }
        }
        hubs.replaceAll { _, value -> value.copy(storedAtMs = 0) }
    }

    /** Invalidation also invalidates in-flight responses, including nested parallel requests. */
    suspend fun <T> withGeneration(block: suspend CoroutineScope.() -> Result<T>): Result<T> {
        val foreground = kotlinx.coroutines.currentCoroutineContext()[com.ambient.tv.data.browse.SpeculativeRequest.Key] == null && requestGeneration.get() == null
        if (foreground) {
            com.ambient.tv.data.browse.BrowseWorkBudget.foregroundRequests.incrementAndGet()
            com.ambient.tv.data.browse.BrowseWorkBudget.speculativeJob?.cancel()
        }
        try {
            restore()
            val expected = requestGeneration.get() ?: generation
            return withContext((if (kotlinx.coroutines.currentCoroutineContext()[com.ambient.tv.data.browse.SpeculativeRequest.Key] != null) kotlin.coroutines.EmptyCoroutineContext else Dispatchers.IO) + requestGeneration.asContextElement(expected)) {
                suspendResult {
                    checkRequestGeneration()
                    val result = block().getOrThrow()
                    checkRequestGeneration()
                    result
                }
            }
        } finally {
            if (foreground) com.ambient.tv.data.browse.BrowseWorkBudget.foregroundRequests.decrementAndGet()
        }
    }

    private fun checkRequestGeneration() {
        syncScope()
        val expected = requestGeneration.get()
        if (expected != null && expected != generation) {
            throw IOException("Media source changed during loading; please retry")
        }
    }

    private data class TimedValue<T>(val value: T, val storedAtMs: Long = System.currentTimeMillis()) {
        fun fresh(maxAgeMs: Long): T? = value.takeIf { System.currentTimeMillis() - storedAtMs <= maxAgeMs }
    }

    @Volatile private var librarySections: TimedValue<List<LibrarySection>>? = null
    @Volatile private var onDeck: TimedValue<List<MediaItem>>? = null
    private val sectionItems = ConcurrentHashMap<String, TimedValue<List<MediaItem>>>()
    private val sectionPreviews = ConcurrentHashMap<String, TimedValue<List<MediaItem>>>()
    private val children = ConcurrentHashMap<String, TimedValue<List<MediaItem>>>()
    private val similar = ConcurrentHashMap<String, TimedValue<List<MediaItem>>>()
    private val filtered = ConcurrentHashMap<String, TimedValue<List<MediaItem>>>()
    private val searches = ConcurrentHashMap<String, TimedValue<List<MediaItem>>>()
    private val hubs = ConcurrentHashMap<String, TimedValue<List<LibraryHub>>>()
    /** Genre id to name per library; the two are the same where a server filters by name. */
    private val genres = ConcurrentHashMap<String, TimedValue<List<Pair<String, String>>>>()
    private val mergedByRatingKey = ConcurrentHashMap<String, MediaItem>()
    /** Keys registered since the last prune; they survive the next prune even when no list holds them yet. */
    private val registeredSincePrune = HashSet<String>()
    private var pruneThreshold = MAX_MERGED_KEYS
    private val resolvedCanonicalKeys = ConcurrentHashMap.newKeySet<String>()

    fun librarySections(): List<LibrarySection>? = run { syncScope(); librarySections?.value }
    fun freshLibrarySections(maxAgeMs: Long): List<LibrarySection>? = run { syncScope(); librarySections?.fresh(maxAgeMs) }
    fun onDeck(): List<MediaItem>? = run { syncScope(); onDeck?.value?.let(::latest) }
    fun freshOnDeck(maxAgeMs: Long): List<MediaItem>? = run { syncScope(); onDeck?.fresh(maxAgeMs)?.let(::latest) }
    fun sectionItems(sectionKey: String): List<MediaItem>? = run { syncScope(); sectionItems[sectionKey]?.value?.let(::latest) }
    fun freshSectionItems(sectionKey: String, maxAgeMs: Long): List<MediaItem>? = run { syncScope(); fresh(sectionItems, sectionKey, maxAgeMs)?.let(::latest) }
    fun sectionPreview(key: String, maxAgeMs: Long): List<MediaItem>? = run { syncScope(); fresh(sectionPreviews, key, maxAgeMs)?.let(::latest) }
    fun children(ratingKey: String, maxAgeMs: Long): List<MediaItem>? = run { syncScope(); fresh(children, ratingKey, maxAgeMs)?.let(::latest) }
    fun similar(ratingKey: String, maxAgeMs: Long): List<MediaItem>? = run { syncScope(); fresh(similar, ratingKey, maxAgeMs)?.let(::latest) }
    fun filtered(key: String, maxAgeMs: Long): List<MediaItem>? = run { syncScope(); fresh(filtered, key, maxAgeMs)?.let(::latest) }
    fun search(key: String, maxAgeMs: Long): List<MediaItem>? = run { syncScope(); fresh(searches, key, maxAgeMs)?.let(::latest) }
    fun hubs(key: String, maxAgeMs: Long): List<LibraryHub>? =
        run { syncScope(); fresh(hubs, key, maxAgeMs)?.map { it.copy(items = latest(it.items)) } }
    fun genres(sectionKey: String, maxAgeMs: Long): List<Pair<String, String>>? = run { syncScope(); fresh(genres, sectionKey, maxAgeMs) }
    fun mediaItem(ratingKey: String): MediaItem? = run { syncScope(); mergedByRatingKey[ratingKey] }

    @Synchronized
    fun setLibrarySections(value: List<LibrarySection>) {
        checkRequestGeneration()
        librarySections = TimedValue(value)
        persist("libraries", BrowseSnapshot(sections = value))
    }

    @Synchronized
    fun setOnDeck(value: List<MediaItem>?) {
        checkRequestGeneration()
        if (value == null) { onDeck = onDeck?.copy(storedAtMs = 0); return }
        onDeck = TimedValue(value)
        persist("ondeck", BrowseSnapshot(items = value))
    }
    @Synchronized
    fun setSectionItems(sectionKey: String, value: List<MediaItem>) {
        checkRequestGeneration()
        putBounded(sectionItems, sectionKey, value, 32)
        persist("section:$sectionKey", BrowseSnapshot(items = value))
    }

    @Synchronized
    fun setSectionPreview(key: String, value: List<MediaItem>) {
        checkRequestGeneration()
        // Every library contributes a handful of previews and one list per recommendation row.
        putBounded(sectionPreviews, key, value, 192)
        persist("preview:$key", BrowseSnapshot(items = value))
    }

    @Synchronized
    fun setHubs(key: String, value: List<LibraryHub>) {
        checkRequestGeneration()
        putBounded(hubs, key, value, 32)
        persist("hubs:$key", BrowseSnapshot(hubs = value))
    }

    @Synchronized
    fun setGenres(sectionKey: String, value: List<Pair<String, String>>) {
        checkRequestGeneration()
        putBounded(genres, sectionKey, value, 32)
    }

    @Synchronized
    fun setChildren(ratingKey: String, value: List<MediaItem>) {
        checkRequestGeneration()
        putBounded(children, ratingKey, value, 128)
        persist("children:$ratingKey", BrowseSnapshot(items = value))
    }

    @Synchronized
    fun setSimilar(ratingKey: String, value: List<MediaItem>) {
        checkRequestGeneration()
        putBounded(similar, ratingKey, value, 128)
        persist("similar:$ratingKey", BrowseSnapshot(items = value))
    }

    @Synchronized
    fun setFiltered(key: String, value: List<MediaItem>) {
        checkRequestGeneration()
        putBounded(filtered, key, value, 32)
        persist("filtered:$key", BrowseSnapshot(items = value))
    }

    @Synchronized
    fun setSearch(key: String, value: List<MediaItem>) {
        checkRequestGeneration()
        putBounded(searches, key, value, 32)
    }

    @Synchronized
    fun clearSection(sectionKey: String) {
        checkRequestGeneration()

        sectionItems.computeIfPresent(sectionKey) { _, value -> value.copy(storedAtMs = 0) }
        sectionPreviews.keys.removeIf { it.startsWith("$sectionKey|") }
        hubs.keys.removeIf { it.startsWith("$sectionKey|") }
        genres.remove(sectionKey)
    }

    @Synchronized
    fun clear() {
        activeScope?.let { disk?.remove(it) }
        clearMemory()
        restored = true
    }

    private fun clearMemory() {
        generation++
        librarySections = null
        onDeck = null
        sectionItems.clear()
        sectionPreviews.clear()
        children.clear()
        similar.clear()
        filtered.clear()
        searches.clear()
        hubs.clear()
        genres.clear()
        mergedByRatingKey.clear()
        registeredSincePrune.clear()
        pruneThreshold = MAX_MERGED_KEYS
        resolvedCanonicalKeys.clear()
    }

    @Synchronized
    fun mergeAndRegister(items: List<MediaItem>): List<MediaItem> {
        checkRequestGeneration()
        return ContentDeduplicator.merge(items).also { merged -> merged.forEach(::registerMerged) }
    }

    @Synchronized
    fun register(item: MediaItem): MediaItem {
        checkRequestGeneration()

        val existing = mergedByRatingKey[item.ratingKey]
        val merged = if (existing != null && existing.canonicalContentKey == item.canonicalContentKey) {
            val otherVersions = existing.mediaVersions.filter { it.sourceRatingKey != null && it.sourceRatingKey !in item.allRatingKeys }
            item.copy(linkedRatingKeys = (item.allRatingKeys + existing.allRatingKeys).distinct(),
                mediaVersions = item.mediaVersions + otherVersions)
        } else {
            item.copy(linkedRatingKeys = item.allRatingKeys)
        }
        registerMerged(merged)
        persist("item:${merged.ratingKey}", BrowseSnapshot(items = listOf(merged)))
        return merged
    }

    /**
     * An edit can change what a title is (new match): every merged entry containing [ratingKeys]
     * is dropped, and duplicates are resolved again from the server the next time it is opened.
     */
    @Synchronized
    fun forgetMerged(ratingKeys: Set<String>) {
        val stale = ratingKeys.mapNotNull(mergedByRatingKey::get)
        stale.forEach { item ->
            item.allRatingKeys.forEach(mergedByRatingKey::remove)
            resolvedCanonicalKeys -= item.canonicalContentKey
        }
    }

    fun mergedFor(ratingKey: String): MediaItem? = mergedByRatingKey[ratingKey]
    fun linkedKeys(ratingKey: String): List<String> = mergedByRatingKey[ratingKey]?.allRatingKeys ?: listOf(ratingKey)
    fun isCanonicalResolved(canonicalKey: String): Boolean = canonicalKey in resolvedCanonicalKeys
    @Synchronized
    fun markCanonicalResolved(canonicalKey: String) {
        checkRequestGeneration()
        resolvedCanonicalKeys += canonicalKey
    }

    @Synchronized
    fun removeFromOnDeck(ratingKeys: Set<String>) {
        checkRequestGeneration()

        onDeck = onDeck?.let { timed -> TimedValue(timed.value.filterNot { item -> item.allRatingKeys.any(ratingKeys::contains) }) }
        onDeck?.let { persist("ondeck", BrowseSnapshot(items = it.value)) }
    }

    fun patchProgress(ratingKeys: Set<String>, positionMs: Long) {
        patchRegistered(ratingKeys) { item ->
            item.copy(viewOffsetMs = if (positionMs == Long.MAX_VALUE) item.durationMs else positionMs.coerceAtLeast(0L))
        }
    }

    fun patchWatched(ratingKeys: Set<String>) {
        patchRegistered(ratingKeys) { item ->
            item.copy(
                viewCount = maxOf(1, item.viewCount ?: 0),
                viewOffsetMs = item.durationMs ?: item.viewOffsetMs,
                // Shows and seasons derive their watched state from the episode counts.
                viewedLeafCount = item.leafCount ?: item.viewedLeafCount,
            )
        }
    }

    fun patchUnwatched(ratingKeys: Set<String>) {
        patchRegistered(ratingKeys) { item ->
            item.copy(viewCount = 0, viewOffsetMs = 0L, viewedLeafCount = if (item.leafCount != null) 0 else item.viewedLeafCount)
        }
    }

    private fun <K, T> fresh(map: ConcurrentHashMap<K, TimedValue<T>>, key: K, maxAgeMs: Long): T? {
        val timed = map[key] ?: return null
        return timed.fresh(maxAgeMs)
    }

    private fun <K, T> putBounded(map: ConcurrentHashMap<K, TimedValue<T>>, key: K, value: T, maxEntries: Int) {
        map[key] = TimedValue(value)
        if (map.size > maxEntries) {
            map.entries.minByOrNull { it.value.storedAtMs }?.let { oldest -> map.remove(oldest.key, oldest.value) }
        }
    }

    @Synchronized
    private fun patchRegistered(ratingKeys: Set<String>, transform: (MediaItem) -> MediaItem) {
        checkRequestGeneration()
        ratingKeys.asSequence()
            .mapNotNull(mergedByRatingKey::get)
            .distinctBy { it.ratingKey }
            .map(transform)
            .forEach(::registerMerged)
    }

    /** Resolve mutable playback/user state only when a cached list is read. This keeps the
     * 10-second progress heartbeat O(number of linked versions), rather than scanning every
     * cached library, preview, search and recommendation list on every tick. */
    private fun latest(items: List<MediaItem>): List<MediaItem> = items.map { item ->
        mergedByRatingKey[item.ratingKey] ?: item
    }

    /** Callers hold the instance lock (every register/patch path is @Synchronized). */
    private fun registerMerged(item: MediaItem) {
        val stable = mergedByRatingKey[item.ratingKey]?.takeIf { it == item } ?: item
        item.allRatingKeys.forEach { key ->
            mergedByRatingKey[key] = stable
            registeredSincePrune += key
        }
        if (mergedByRatingKey.size > pruneThreshold) pruneMerged()
    }

    /**
     * The merged map otherwise grows with every library page ever seen. Entries still referenced by
     * a cached list, or registered since the previous prune (a page whose list is not stored yet),
     * stay; everything else is dropped. Lookups fall back to the server for dropped items. The next
     * prune waits until the map has doubled again, so a large referenced set does not prune per call.
     */
    private fun pruneMerged() {
        val keep = HashSet<String>(registeredSincePrune)
        fun reference(items: List<MediaItem>) = items.forEach { keep += it.ratingKey }
        onDeck?.value?.let(::reference)
        listOf(sectionItems, sectionPreviews, children, similar, filtered, searches).forEach { map ->
            map.values.forEach { reference(it.value) }
        }
        hubs.values.forEach { timed -> timed.value.forEach { reference(it.items) } }
        mergedByRatingKey.entries.removeIf { (_, item) -> item.allRatingKeys.none(keep::contains) }
        registeredSincePrune.clear()
        pruneThreshold = maxOf(MAX_MERGED_KEYS, mergedByRatingKey.size * 2)
    }

    private companion object {
        const val MAX_MERGED_KEYS = 8_192
    }
}
