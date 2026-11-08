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

/**
 * An explicitly refreshed view follows the source's new order. A partial response only owns its
 * delivered prefix: rows and videos that have not arrived yet stay behind it until completion.
 */
internal fun sourceOrderedYouTubeRows(
    previous: List<YouTubeRow>,
    incoming: List<YouTubeRow>,
    complete: Boolean,
): List<YouTubeRow> {
    val freshRows = incoming.distinctBy { it.id }
    if (complete) return freshRows
    val previousById = previous.associateBy { it.id }
    // At capacity, a partial insertion must not evict a still-pending cached row or video.
    // Additional new IDs wait for completion, which is allowed to replace membership.
    val newRowIds = freshRows.filter { it.id !in previousById }
        .take((YouTubeContentCache.MAX_ROWS - previousById.size).coerceAtLeast(0))
        .mapTo(HashSet()) { it.id }
    val availableRows = freshRows.filter { it.id in previousById || it.id in newRowIds }
    val freshIds = availableRows.mapTo(HashSet()) { it.id }
    return availableRows.map { row ->
        val oldItems = previousById[row.id]?.items.orEmpty()
        val oldVideoIds = oldItems.mapTo(HashSet()) { it.videoId }
        val incomingItems = row.items.distinctBy { it.videoId }
        val newVideoIds = incomingItems.filter { it.videoId !in oldVideoIds }
            .take((YouTubeContentCache.MAX_ITEMS_PER_ROW - oldVideoIds.size).coerceAtLeast(0))
            .mapTo(HashSet()) { it.videoId }
        val freshItems = incomingItems.filter { it.videoId in oldVideoIds || it.videoId in newVideoIds }
        val freshVideoIds = freshItems.mapTo(HashSet()) { it.videoId }
        row.copy(items = (freshItems + oldItems
            .filter { it.videoId !in freshVideoIds }).distinctBy { it.videoId })
    } + previous.filter { it.id !in freshIds }
}

/** Only a completed feed replaces disk membership. Partial responses remain process-local. */
@Singleton
class YouTubeContentCache() {
    @Inject constructor(disk: BrowseDiskStore, engine: SmartTubeEngine) : this(disk, { engine.browseScope() })
    internal constructor(disk: BrowseDiskStore, scope: suspend () -> String) : this() {
        this.disk = disk
        scopeResolver = scope
    }
    private val entries = LinkedHashMap<String, List<YouTubeRow>>(16, 0.75f, true)
    private val details = LinkedHashMap<String, YouTubeMetadata>(16, .75f, true)
    /** Server order of the last completed load per key, adopted when the key is opened again. */
    private val freshOrder = HashMap<String, List<YouTubeRow>>()
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
                entries.clear(); details.clear(); freshOrder.clear(); source = next
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
    @Synchronized fun merge(
        key: String,
        incoming: List<YouTubeRow>,
        expected: Long = generation,
        preferIncomingOrder: Boolean = false,
    ): List<YouTubeRow> {
        if (expected != generation) return emptyList()
        val previous = get(key).orEmpty()
        val rows = if (preferIncomingOrder) {
            // This newer visible update supersedes an older deferred completed ordering.
            freshOrder.remove(key)
            sourceOrderedYouTubeRows(previous, incoming, complete = false)
        } else stableYouTubeRows(previous, incoming, complete = false)
        return storeRows(key, rows)
    }

    /**
     * Appends a continuation page to one row in a single step (deduplicated by videoId). Process-local
     * like partial responses: disk keeps the first page that the last completed load returned.
     */
    @Synchronized fun appendItems(key: String, rowId: String, items: List<YouTubeItem>, expected: Long = generation): List<YouTubeRow>? {
        if (expected != generation) return null
        val current = get(key) ?: return null
        val appended = appendYouTubeRowItems(current, rowId, items)
        // Reopening a deferred refresh must not discard pages loaded after that refresh.
        freshOrder[key]?.let { freshOrder[key] = appendYouTubeRowItems(it, rowId, items) }
        return if (appended === current) current else storeRows(key, appended)
    }

    @Synchronized fun replace(
        key: String,
        incoming: List<YouTubeRow>,
        expected: Long = generation,
        preferIncomingOrder: Boolean = false,
    ): List<YouTubeRow> {
        if (expected != generation) return emptyList()
        val previous = get(key).orEmpty()
        val ordered = if (preferIncomingOrder) sourceOrderedYouTubeRows(previous, incoming, complete = true)
            else stableYouTubeRows(previous, incoming, complete = true)
        val rows = storeRows(key, reconcileComplete(previous, ordered) { it.id })
        // Persist the server's current ordering for the next cold start; the active view
        // keeps stable ordering unless its caller explicitly applies the new source order.
        persist(key, incoming, expected)
        if (preferIncomingOrder) freshOrder.remove(key) else freshOrder[key] = incoming
        return rows
    }

    /**
     * A feed that stopped early (emission or time limit) still delivered usable rows. Keep them as
     * display state for the next opening: merged like a partial response, so nothing that is
     * already cached gets deleted, and written to disk unlike other partial responses.
     */
    @Synchronized fun persistPartial(
        key: String,
        incoming: List<YouTubeRow>,
        expected: Long = generation,
        preferIncomingOrder: Boolean = false,
    ): List<YouTubeRow> {
        if (expected != generation) return emptyList()
        if (incoming.isEmpty()) return get(key).orEmpty()
        val rows = merge(key, incoming, expected, preferIncomingOrder)
        // An older completed snapshot must not replace the newer retained partial state.
        freshOrder.remove(key)
        persist(key, rows, expected)
        return rows
    }

    private fun persist(key: String, rows: List<YouTubeRow>, expected: Long) {
        val namespace = source ?: return
        disk?.put(namespace, "rows:$key", { generation == expected }) {
            com.ambient.tv.data.browse.encodeBrowseJson(json, rows.take(MAX_ROWS).map { row -> row.copy(items = row.items.take(MAX_ITEMS_PER_ROW).map { it.copy(
                imageUrl = com.ambient.tv.data.browse.displayArtworkUrl(it.imageUrl),
                backgroundUrl = com.ambient.tv.data.browse.displayArtworkUrl(it.backgroundUrl),
            ) }) })
        }
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
                durationMs = it.durationMs, percentWatched = it.percentWatched, isLive = it.isLive,
                publishedDate = youtubePublicationText(it.publishedText),
                // Feed dates inferred from "3 days ago" must not masquerade as exact times.
                publishedAtMs = it.publishedAtMs.takeIf { _ -> youtubePublicationText(it.publishedText) == null }
                    ?.let(::youtubePublicationEpochMs))
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

    /**
     * Start of a new visit: the rows take the server's order of the last completed load. Without
     * this, new videos (subscriptions) stayed appended at the end of the old order all session.
     */
    @Synchronized fun adoptFreshOrder(key: String) {
        freshOrder.remove(key)?.let { storeRows(key, it) }
    }

    /** Drops [key] from memory (not from disk) and returns what was there. */
    @Synchronized fun forget(key: String): List<YouTubeRow>? {
        freshOrder.remove(key)
        return entries.remove(key)
    }

    @Synchronized fun clear() {
        generation++
        entries.clear(); details.clear(); freshOrder.clear()
        source?.let { disk?.remove(it) }
        source = null
    }

    internal companion object {
        const val MAX_ENTRIES = 24
        const val MAX_ROWS = 24
        const val MAX_ITEMS_PER_ROW = 120
    }
}
