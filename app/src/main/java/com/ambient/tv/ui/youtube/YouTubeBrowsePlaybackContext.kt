package com.ambient.tv.ui.youtube

import com.ambient.tv.youtube.SmartTubeEngine
import com.ambient.tv.youtube.YouTubeContentCache
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The originating browse row owns automatic playback. Metadata suggestions are deliberately
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

    private class Session(
        val token: Long,
        val scope: String,
        val rowId: String,
        val items: MutableList<YouTubeItem>,
        val playerScope: String,
        val continuationRows: List<String>,
        val forkedRows: MutableSet<String>,
        val exhaustedRows: MutableSet<String> = mutableSetOf(),
        var lastPlayed: String? = null,
        var returnPending: Boolean = false,
    )

    private var nextToken = 0L
    private var current: Session? = null
    private val continuationMutex = Mutex()

    @Synchronized
    fun start(
        scope: String?,
        row: YouTubeRow?,
        videoId: String,
        continuationRowIds: List<String> = listOfNotNull(row?.id),
        returnRowId: String? = row?.id,
    ): Long? {
        if (scope.isNullOrBlank() || row == null || row.items.none { it.videoId == videoId }) return null
        current?.let { engine.discardRowContinuation(it.playerScope) }
        val token = ++nextToken
        val playerScope = "youtube-player-row:$token"
        val sourceRows = continuationRowIds.filter(String::isNotBlank).distinct()
            .ifEmpty { listOf(row.id) }
        val forked = sourceRows.filter { engine.forkRowContinuation(scope, playerScope, it) }.toMutableSet()
        current = Session(
            token = token, scope = scope, rowId = returnRowId ?: row.id,
            items = row.items.distinctBy { it.videoId }.toMutableList(),
            playerScope = playerScope, continuationRows = sourceRows, forkedRows = forked,
        )
        return token
    }

    @Synchronized
    fun contains(token: Long, videoId: String): Boolean =
        current?.takeIf { it.token == token }?.items?.any { it.videoId == videoId } == true

    @Synchronized
    fun played(token: Long, videoId: String) {
        val session = current?.takeIf { it.token == token } ?: return
        if (session.items.none { it.videoId == videoId }) return
        session.lastPlayed = videoId
        session.returnPending = true
    }

    /** A return address is claimed only by the exact original feed/channel/search scope. */
    @Synchronized
    fun consumeReturn(scope: String): ReturnTarget? {
        val session = current?.takeIf { it.scope == scope && it.returnPending } ?: return null
        val videoId = session.lastPlayed ?: return null
        session.returnPending = false
        return ReturnTarget(session.rowId, videoId)
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
            synchronized(this) {
                current?.takeIf { it === source && it.token == token }?.let { session ->
                    if (page == null || !page.hasMore) session.exhaustedRows.add(rowId)
                    if (page != null) {
                        val known = session.items.mapTo(HashSet()) { it.videoId }
                        session.items += page.items.filter { known.add(it.videoId) }
                        // Put continuation items back in their actual source row on return.
                        if (page.items.isNotEmpty()) cache.appendItems(session.scope, rowId, page.items)
                    }
                }
            }
            nextLoaded(token, videoId)?.let { return@withLock it }
        }
        nextLoaded(token, videoId)
    }

    private companion object {
        const val MAX_EMPTY_OR_FILTERED_PAGES = 8
    }
}
