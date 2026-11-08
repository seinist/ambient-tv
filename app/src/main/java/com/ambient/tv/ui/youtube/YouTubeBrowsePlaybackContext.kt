package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.appendYouTubeRowItems
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The originating browse row owns sequential playback. Metadata suggestions are deliberately
 * NOT part of this queue: an Abos/History/Channel run must never silently become Home.
 * A token makes a restored or separately launched player unable to borrow a stale browse queue.
 */
/** Source-relative navigation ignores only non-playable tiles, never recommendations. */
internal fun previousPlayableBrowseVideo(items: List<YouTubeItem>, currentVideoId: String): YouTubeItem? {
    val index = items.indexOfFirst { it.videoId == currentVideoId }
    if (index <= 0) return null
    return items.subList(0, index).asReversed()
        .firstOrNull { !it.isChannel && !it.isLive && !it.isUpcoming }
}

/** A strictly adjacent playable successor in this same row, never a global suggestion. */
internal fun nextPlayableBrowseVideo(items: List<YouTubeItem>, currentVideoId: String): YouTubeItem? {
    val index = items.indexOfFirst { it.videoId == currentVideoId }
    if (index < 0) return null
    return items.asSequence().drop(index + 1)
        .firstOrNull { !it.isChannel && !it.isLive && !it.isUpcoming }
}

@Singleton
class YouTubeBrowsePlaybackContext @Inject constructor(
    private val engine: SmartTubeEngine,
    private val cache: YouTubeContentCache,
) {
    data class ReturnTarget(val rowId: String, val videoId: String)

    /**
     * Read without claiming the return, so the destination can build its first layout at the
     * last played item. Rows belong to this playback visit, not a newer background refresh.
     */
    data class ReturnSnapshot(
        val scope: String,
        val revision: Int,
        val cacheRevision: Long,
        val target: ReturnTarget,
        val rows: List<YouTubeRow>,
        val sessionToken: Long = 0L,
    )

    private class Session(
        val token: Long,
        val scope: String,
        val rowId: String,
        val items: MutableList<YouTubeItem>,
        val playerScope: String,
        val continuationRows: List<String>,
        val forkedRows: MutableSet<String>,
        val sort: YouTubeSort,
        val filter: YouTubeFilter,
        val descending: Boolean,
        val isTrailer: Boolean,
        val cacheRevision: Long,
        var returnRows: List<YouTubeRow>,
        val exhaustedRows: MutableSet<String> = mutableSetOf(),
        var lastPlayed: String? = null,
        var returnPending: Boolean = false,
    )

    private var nextToken = 0L
    private var nextReturnRevision = 0
    private var current: Session? = null
    private val continuationMutex = Mutex()
    private val _pendingReturn = MutableStateFlow<ReturnSnapshot?>(null)
    val pendingReturn: StateFlow<ReturnSnapshot?> = _pendingReturn.asStateFlow()

    @Synchronized
    fun start(
        scope: String?,
        row: YouTubeRow?,
        videoId: String,
        continuationRowIds: List<String> = listOfNotNull(row?.id),
        returnRowId: String? = row?.id,
        sort: YouTubeSort = YouTubeSort.DEFAULT,
        filter: YouTubeFilter = YouTubeFilter.ALL,
        descending: Boolean = false,
        isTrailer: Boolean = false,
        sourceRows: List<YouTubeRow> = listOfNotNull(row),
    ): Long? {
        if (scope.isNullOrBlank() || row == null || row.items.none { it.videoId == videoId }) return null
        current?.let { engine.discardRowContinuation(it.playerScope) }
        val token = ++nextToken
        val playerScope = "youtube-player-row:$token"
        // TMDB supplies a complete, finite row. Never ask SmartTube for a continuation
        // or borrow a YouTube feed's paging handle for this trailer session.
        val continuationSources = if (isTrailer) emptyList() else continuationRowIds.filter(String::isNotBlank).distinct()
            .ifEmpty { listOf(row.id) }
        val forked = continuationSources.filter { engine.forkRowContinuation(scope, playerScope, it) }.toMutableSet()
        current = Session(
            token = token, scope = scope, rowId = returnRowId ?: row.id,
            items = row.items.distinctBy { it.videoId }.toMutableList(),
            playerScope = playerScope, continuationRows = continuationSources, forkedRows = forked,
            sort = sort, filter = filter, descending = descending, isTrailer = isTrailer,
            cacheRevision = cache.revision(),
            returnRows = sourceRows.ifEmpty { listOf(row) }.distinctBy { it.id }
                .map { it.copy(items = it.items.toList()) },
        )
        _pendingReturn.value = null
        return token
    }

    @Synchronized
    fun contains(token: Long, videoId: String): Boolean =
        current?.takeIf { it.token == token }?.items?.any { it.videoId == videoId } == true

    @Synchronized
    fun containsTrailer(token: Long, videoId: String): Boolean =
        current?.takeIf { it.token == token && it.isTrailer }?.items?.any { it.videoId == videoId } == true

    @Synchronized
    fun played(token: Long, videoId: String) {
        val session = current?.takeIf { it.token == token } ?: return
        if (session.items.none { it.videoId == videoId }) return
        session.lastPlayed = videoId
        session.returnPending = true
        _pendingReturn.value = returnSnapshot(session, ++nextReturnRevision)
    }

    /** A return address is claimed only by the exact original feed/channel/search scope. */
    @Synchronized
    fun consumeReturn(scope: String): ReturnTarget? {
        val snapshot = _pendingReturn.value?.takeIf { it.scope == scope } ?: return null
        val session = current?.takeIf { it.scope == scope && it.returnPending } ?: return null
        session.returnPending = false
        _pendingReturn.value = null
        return snapshot.target
    }

    /**
     * Commit the prepared destination before removing its preview. A stale composition must
     * not claim a later Next/Previous event, even when the video and source scope are the same.
     */
    @Synchronized
    internal fun commitReturn(scope: String, revision: Int, commit: (ReturnSnapshot) -> Unit): Boolean {
        val snapshot = _pendingReturn.value?.takeIf { it.scope == scope && it.revision == revision }
            ?: return false
        val session = current?.takeIf { it.scope == scope && it.returnPending } ?: return false
        commit(snapshot)
        session.returnPending = false
        _pendingReturn.value = null
        return true
    }

    private fun returnSnapshot(session: Session, revision: Int): ReturnSnapshot {
        val videoId = requireNotNull(session.lastPlayed)
        // A flattened view can continue into a different source row. Preserve that actual
        // return address instead of always reusing the row of the original click.
        val row = session.returnRows.firstOrNull { row ->
            row.id == session.rowId && row.items.any { it.videoId == videoId }
        } ?: session.returnRows.firstOrNull { row -> row.items.any { it.videoId == videoId } }
        return ReturnSnapshot(session.scope, revision, session.cacheRevision,
            ReturnTarget(row?.id ?: session.rowId, videoId), session.returnRows, session.token)
    }

    @Synchronized
    fun previousLoaded(token: Long, videoId: String): YouTubeItem? {
        val session = current?.takeIf { it.token == token } ?: return null
        return previousPlayableBrowseVideo(session.items, videoId)
    }

    @Synchronized
    fun nextLoaded(token: Long, videoId: String): YouTubeItem? {
        val session = current?.takeIf { it.token == token } ?: return null
        return nextPlayableBrowseVideo(session.items, videoId)
    }

    /**
     * Continue only the originating row(s) while the player is open. A flat grid can
     * combine several source rows: each keeps its own frozen continuation handle.
     * Exhausting one row must not silently replace the queue with another feed.
     */
    suspend fun next(token: Long, videoId: String): YouTubeItem? = continuationMutex.withLock {
        val rowCount = synchronized(this) { current?.takeIf { it.token == token }?.continuationRows?.size ?: 0 }
        repeat(MAX_EMPTY_OR_FILTERED_PAGES * rowCount.coerceAtLeast(1)) {
            nextLoaded(token, videoId)?.let { return@withLock it }
            val source = synchronized(this) {
                current?.takeIf { it.token == token && it.items.any { item -> item.videoId == videoId } }
            } ?: return@withLock null
            val rowId = synchronized(this) {
                source.continuationRows.firstOrNull { it !in source.exhaustedRows }
            } ?: return@withLock null

            // The initial browse page may have been displayed from disk. Reacquire a handle
            // for that exact source row if the upstream feed finished loading in the meantime.
            if (rowId !in source.forkedRows && engine.forkRowContinuation(source.scope, source.playerScope, rowId)) {
                synchronized(this) { source.forkedRows.add(rowId) }
            }
            val page = engine.continueRow(
                if (rowId in source.forkedRows) source.playerScope else source.scope,
                rowId,
            )
            appendContinuation(token, rowId, page)
            nextLoaded(token, videoId)?.let { return@withLock it }
        }
        nextLoaded(token, videoId)
    }

    /**
     * Adds a lazily loaded page to the queue exactly as the browse page shows it (same sort
     * and filter). Played items of such a page are valid return targets like the first page.
     */
    @Synchronized
    internal fun appendContinuation(token: Long, rowId: String, page: com.ambient.tv.youtube.YouTubeRowPage?) {
        val session = current?.takeIf { it.token == token } ?: return
        if (session.isTrailer) return
        if (page == null || !page.hasMore) session.exhaustedRows.add(rowId)
        if (page == null) return
        val known = session.items.mapTo(HashSet()) { it.videoId }
        val visible = youtubePlayableContinuationItems(page.items, session.sort, session.filter, session.descending)
        session.items += visible.filter { known.add(it.videoId) }
        // Make every played page available before the returning destination's first measure,
        // even if its cache entry was evicted or a background refresh changed its order.
        session.returnRows = appendYouTubeRowItems(session.returnRows, rowId, page.items, maxItems = Int.MAX_VALUE)
        if (session.returnPending) {
            _pendingReturn.value?.let { pending ->
                _pendingReturn.value = returnSnapshot(session, pending.revision)
            }
        }
        // Put raw continuation items back in their actual source row on return. A queue from
        // the previous account/language may never append into a newer cache generation.
        if (page.items.isNotEmpty()) cache.appendItems(session.scope, rowId, page.items, session.cacheRevision)
    }

    private companion object {
        const val MAX_EMPTY_OR_FILTERED_PAGES = 8
    }
}
