package com.ambient.tv.youtube

import com.ambient.tv.data.browse.BrowseDiskStore
import com.ambient.tv.data.browse.reconcileComplete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preserve the displayed row and tile order while new source results arrive. New IDs are
 * appended without dislodging the focused row; complete results still remove deleted IDs.
 * Incoming metadata replaces old values, but indices never become the identity of an item.
 */
internal fun stableYouTubeRows(
    previous: List<YouTubeRow>,
    incoming: List<YouTubeRow>,
    complete: Boolean,
): List<YouTubeRow> {
    if (previous.isEmpty()) return incoming.distinctBy { it.id }
    val newById = incoming.associateBy { it.id }
    val oldIds = previous.mapTo(HashSet()) { it.id }
    val ordered = previous.filter { !complete || it.id in newById } +
        incoming.filter { it.id !in oldIds }
    return ordered.map { old ->
        val fresh = newById[old.id] ?: return@map old
        val before = previous.firstOrNull { it.id == old.id }?.items.orEmpty()
        val freshItems = fresh.items.associateBy { it.videoId }
        val oldVideoIds = before.mapTo(HashSet()) { it.videoId }
        val retained = before.mapNotNull { item ->
            freshItems[item.videoId] ?: item.takeUnless { complete }
        }
        fresh.copy(items = (retained + fresh.items.filter { it.videoId !in oldVideoIds })
            .distinctBy { it.videoId })
    }
}

/** Only a completed feed replaces disk membership. Partial responses remain process-local. */
@Singleton
class YouTubeContentCache() {
    @Inject constructor(disk: BrowseDiskStore, engine: SmartTubeEngine) : this() {
        this.disk = disk
        scopeResolver = { engine.browseScope() }
    }
    private val entries = LinkedHashMap<String, List<YouTubeRow>>(16, 0.75f, true)
    private val details = LinkedHashMap<String, YouTubeMetadata>(16, .75f, true)
    private var disk: BrowseDiskStore? = null
    private var scopeResolver: (suspend () -> String)? = null
    @Volatile private var source: String? = null
    @Volatile private var generation = 0L
    private val restoreLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun restore() = withContext(Dispatchers.IO) {
        restoreLock.withLock {
            val next = scopeResolver?.invoke() ?: return@withLock
            if (next == source) return@withLock
            synchronized(this@YouTubeContentCache) {
                source?.let { disk?.remove(it) }
                generation++
                entries.clear(); details.clear(); source = next
            }
            val expected = generation
            val stored = disk?.read(next).orEmpty()
            val rows = stored.filterKeys { it.startsWith("rows:") }.mapNotNull { (key, value) ->
                runCatching { key.removePrefix("rows:") to json.decodeFromString<List<YouTubeRow>>(value) }.getOrNull()
            }
            val metadata = stored.filterKeys { it.startsWith("detail:") }.values.mapNotNull {
                runCatching { json.decodeFromString<YouTubeMetadata>(it) }.getOrNull()
            }
            synchronized(this@YouTubeContentCache) {
                if (generation == expected) {
                    rows.take(MAX_ENTRIES).forEach { (key, value) -> entries.putIfAbsent(key, value) }
                    metadata.take(128).forEach { details.putIfAbsent(it.videoId, it) }
                }
            }
        }
    }

    @Synchronized fun get(key: String): List<YouTubeRow>? = entries[key]
    @Synchronized fun revision(): Long = generation

    /** Partial response updates existing videos, retaining absent rows/items until completion. */
    @Synchronized fun merge(key: String, incoming: List<YouTubeRow>, expected: Long = generation): List<YouTubeRow> {
        if (expected != generation) return emptyList()
        val previous = get(key).orEmpty()
        return storeRows(key, stableYouTubeRows(previous, incoming, complete = false))
    }

    /**
     * Appends a continuation page to one row in a single step (deduplicated by videoId). Process-local
     * like partial responses: disk keeps the first page that the last completed load returned.
     */
    @Synchronized fun appendItems(key: String, rowId: String, items: List<YouTubeItem>, expected: Long = generation): List<YouTubeRow>? {
        if (expected != generation) return null
        val current = get(key) ?: return null
        val appended = appendYouTubeRowItems(current, rowId, items)
        return if (appended === current) current else storeRows(key, appended)
    }

    /**
     * [keepVisible]: an automatic refresh of tiles already on screen (cold start, entry). Their
     * order and membership stay; new videos are appended. YouTube's recommendations differ on
     * every request, so a complete replace swapped nearly every tile under the user's focus.
     * Disk still receives the fresh server result, which the next start shows.
     */
    @Synchronized fun replace(
        key: String,
        incoming: List<YouTubeRow>,
        expected: Long = generation,
        keepVisible: Boolean = false,
    ): List<YouTubeRow> {
        if (expected != generation) return emptyList()
        val previous = get(key).orEmpty()
        val rows = storeRows(key, if (keepVisible && previous.isNotEmpty())
            stableYouTubeRows(previous, incoming, complete = false)
        else reconcileComplete(previous, stableYouTubeRows(previous, incoming, complete = true)) { it.id })
        val namespace = source
        if (namespace != null) disk?.put(namespace, "rows:$key", { generation == expected }) {
            // Persist the server's current ordering for the next cold start; the active view
            // uses stable ordering until the user leaves, avoiding a reflow under focus.
            com.ambient.tv.data.browse.encodeBrowseJson(json, incoming.take(MAX_ROWS).map { row -> row.copy(items = row.items.take(MAX_ITEMS_PER_ROW).map { it.copy(
                imageUrl = com.ambient.tv.data.browse.displayArtworkUrl(it.imageUrl),
                backgroundUrl = com.ambient.tv.data.browse.displayArtworkUrl(it.backgroundUrl),
            ) }) })
        }
        return rows
    }

    private fun storeRows(key: String, rows: List<YouTubeRow>): List<YouTubeRow> {
        val bounded = rows.take(MAX_ROWS).map { it.copy(items = it.items.distinctBy { item -> item.videoId }.take(MAX_ITEMS_PER_ROW)) }
        entries[key] = bounded
        while (entries.size > MAX_ENTRIES) entries.remove(entries.entries.first().key)
        return bounded
    }

    @Synchronized fun metadata(id: String): YouTubeMetadata? = details[id] ?: entries.values.asSequence()
        .flatMap { it.asSequence() }.flatMap { it.items.asSequence() }.firstOrNull { it.videoId == id }?.let {
            YouTubeMetadata(it.videoId, it.title, it.author, imageUrl = it.imageUrl, backgroundUrl = it.backgroundUrl,
                durationMs = it.durationMs, percentWatched = it.percentWatched, isLive = it.isLive)
        }

    @Synchronized fun putMetadata(value: YouTubeMetadata, expected: Long) {
        if (expected != generation) return
        details[value.videoId] = value
        while (details.size > 128) details.remove(details.keys.first())
        val namespace = source ?: return
        disk?.put(namespace, "detail:${value.videoId}", { generation == expected }) {
            com.ambient.tv.data.browse.encodeBrowseJson(json, value.copy(imageUrl = com.ambient.tv.data.browse.displayArtworkUrl(value.imageUrl),
                backgroundUrl = com.ambient.tv.data.browse.displayArtworkUrl(value.backgroundUrl), suggestions = emptyList(), chapters = emptyList()))
        }
    }

    @Synchronized fun clear() {
        generation++
        entries.clear(); details.clear()
        source?.let { disk?.remove(it) }
        source = null
    }

    internal companion object {
        const val MAX_ENTRIES = 24
        const val MAX_ROWS = 24
        const val MAX_ITEMS_PER_ROW = 120
    }
}
