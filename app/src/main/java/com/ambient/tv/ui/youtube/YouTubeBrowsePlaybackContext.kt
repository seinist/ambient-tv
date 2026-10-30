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
        var exhausted: Boolean = false,
        var lastPlayed: String? = null,
        var returnPending: Boolean = false,
    )

    private var nextToken = 0L
    private var current: Session? = null
    private val continuationMutex = Mutex()

    @Synchronized
    fun start(scope: String?, row: YouTubeRow?, videoId: String): Long? {
        if (scope.isNullOrBlank() || row == null || row.items.none { it.videoId == videoId }) return null
        val token = ++nextToken
        current = Session(token, scope, row.id, row.items.distinctBy { it.videoId }.toMutableList())
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

    private fun playable(item: YouTubeItem): Boolean =
        !item.isChannel && !item.isLive && !item.isUpcoming

    @Synchronized
    fun nextLoaded(token: Long, videoId: String): YouTubeItem? {
        val session = current?.takeIf { it.token == token } ?: return null
        val index = session.items.indexOfFirst { it.videoId == videoId }
        if (index < 0) return null
        return session.items.asSequence().drop(index + 1).firstOrNull(::playable)
    }

    /**
     * Continue the ORIGINAL row while the player is open. A missing continuation handle means
     * that row really has no next page; errors propagate and do not mark it exhausted.
     * Bounded duplicate-only pages prevent an upstream feed from looping forever.
     */
    suspend fun next(token: Long, videoId: String): YouTubeItem? = continuationMutex.withLock {
        repeat(MAX_EMPTY_OR_FILTERED_PAGES) {
            nextLoaded(token, videoId)?.let { return@withLock it }
            val source = synchronized(this) {
                current?.takeIf { it.token == token && !it.exhausted && it.items.any { item -> item.videoId == videoId } }
                    ?.let { it.scope to it.rowId }
            } ?: return@withLock null
            val page = engine.continueRow(source.first, source.second)
            synchronized(this) {
                current?.takeIf { it.token == token && it.scope == source.first && it.rowId == source.second }?.let { session ->
                    if (page == null) {
                        session.exhausted = true
                    } else {
                        val known = session.items.mapTo(HashSet()) { it.videoId }
                        session.items += page.items.filter { known.add(it.videoId) }
                        session.exhausted = !page.hasMore
                        // Return browsing can show videos loaded while the player was foreground.
                        if (page.items.isNotEmpty()) cache.appendItems(session.scope, session.rowId, page.items)
                    }
                }
            }
            if (page == null || !page.hasMore) return@withLock nextLoaded(token, videoId)
        }
        nextLoaded(token, videoId)
    }

    private companion object {
        const val MAX_EMPTY_OR_FILTERED_PAGES = 8
    }
}
