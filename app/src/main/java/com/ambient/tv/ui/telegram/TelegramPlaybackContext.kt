package com.ambient.tv.ui.telegram

import com.ambient.tv.data.telegram.TelegramFloodWaitException
import com.ambient.tv.data.telegram.TelegramMedia
import com.ambient.tv.data.telegram.TelegramMediaKind
import com.ambient.tv.data.telegram.TelegramMediaPage
import com.ambient.tv.data.telegram.TelegramRepository
import com.ambient.tv.data.telegram.telegramFloodWaitSeconds
import com.ambient.tv.data.telegram.telegramMediaIds
import com.ambient.tv.domain.model.MediaItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How the originating page shows its posts, so a page loaded by the player joins the queue in
 * the same order and with the same filter as on screen. [rowId] limits the queue to one row
 * (a channel row of the "recent" page); null means the whole flattened grid/list.
 */
internal data class TelegramQueueView(
    val query: String = "",
    val channelRowId: String? = null,
    val sort: MonogramBrowseSort = MonogramBrowseSort.DATE,
    val descending: Boolean = true,
    val rowId: String? = null,
)

/**
 * Only Telegram's own video and audio files can be queued. Photos, YouTube link cards,
 * protected posts and anything without a post id are skipped; Telegram live streams and
 * scheduled posts never reach the browse pages as playable files.
 */
internal fun telegramQueuePlayable(item: MediaItem, media: TelegramMedia? = null): Boolean {
    if (telegramMediaIds(item.ratingKey) == null) return false
    if (media != null) return media.playableOnTelegram &&
        (media.kind == TelegramMediaKind.VIDEO || media.kind == TelegramMediaKind.AUDIO)
    return item.type == "movie" || item.type == "track"
}

/** The posts a lazily loaded page adds to the queue, exactly as the originating page shows them. */
internal fun telegramQueuePageItems(section: String, page: List<TelegramMedia>, view: TelegramQueueView): List<MediaItem> {
    val rows = visibleMonogramRows(telegramRows(section, page), view.query, view.channelRowId, view.sort, view.descending)
    val visible = if (view.rowId != null) rows.firstOrNull { it.id == view.rowId }?.items.orEmpty()
        else flatMonogramItems(rows, view.sort, view.descending)
    val byKey = page.associateBy(TelegramMedia::mediaKey)
    return visible.filter { telegramQueuePlayable(it, byKey[it.ratingKey]) }
}

/**
 * The page a Telegram post was started from (channel, section, Home row) owns automatic
 * playback, like [com.ambient.tv.ui.youtube.YouTubeBrowsePlaybackContext]: the queue is the
 * page as shown, never suggestions, with no wrap-around. The player carries the session token
 * in its route, so a restored or separately launched player cannot borrow a stale queue.
 */
@Singleton
class TelegramPlaybackContext internal constructor(
    private val loadPage: suspend (section: String, cursor: String) -> TelegramMediaPage,
    private val nowMs: () -> Long,
    firstToken: Long,
) {
    @Inject constructor(repository: TelegramRepository) : this(
        loadPage = { section, cursor -> repository.browsePage(section, cursor) },
        nowMs = { System.nanoTime() / 1_000_000L },
        // Tokens of a previous process (restored routes) never match a new session.
        firstToken = java.util.concurrent.ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE / 4),
    )

    /** Where the page returns to after playback, plus the pages the player loaded meanwhile. */
    data class ReturnTarget(
        val mediaKey: String,
        val rowId: String?,
        val loadedMedia: List<TelegramMedia>,
        val startCursor: String?,
        val nextCursor: String?,
    )

    private class Session(
        val token: Long,
        val scope: String,
        val section: String?,
        val view: TelegramQueueView,
        val items: MutableList<MediaItem>,
        val startCursor: String?,
        var cursor: String?,
        val loadedMedia: MutableList<TelegramMedia> = mutableListOf(),
        var pagesLoaded: Int = 0,
        var lastPlayed: String? = null,
        var returnPending: Boolean = false,
    )

    private var nextToken = firstToken
    private var current: Session? = null
    private var blockedUntilMs = 0L
    private val pageMutex = Mutex()

    /**
     * [origin] is the row or flattened grid/list exactly as shown. [section] and [cursor] let
     * the player continue that page; without them the queue ends with the loaded posts.
     */
    @Synchronized
    internal fun start(
        scope: String,
        origin: List<MediaItem>,
        selected: String,
        media: Map<String, TelegramMedia> = emptyMap(),
        section: String? = null,
        cursor: String? = null,
        view: TelegramQueueView = TelegramQueueView(),
    ): Long? {
        val items = origin.distinctBy(MediaItem::ratingKey)
            .filter { telegramQueuePlayable(it, media[it.ratingKey]) }
            .take(MAX_QUEUE_ITEMS)
        if (scope.isBlank() || items.none { it.ratingKey == selected }) return null
        val token = ++nextToken
        current = Session(
            token = token, scope = scope, section = section, view = view,
            items = items.toMutableList(), startCursor = cursor,
            cursor = cursor.takeIf { section != null },
        )
        return token
    }

    @Synchronized
    fun contains(token: Long, mediaKey: String): Boolean =
        current?.takeIf { it.token == token }?.items?.any { it.ratingKey == mediaKey } == true

    @Synchronized
    fun played(token: Long, mediaKey: String) {
        val session = current?.takeIf { it.token == token } ?: return
        if (session.items.none { it.ratingKey == mediaKey }) return
        session.lastPlayed = mediaKey
        session.returnPending = true
    }

    /** Claimed once, and only by the exact page ([scope]) the queue came from. */
    @Synchronized
    fun consumeReturn(scope: String): ReturnTarget? {
        val session = current?.takeIf { it.scope == scope && it.returnPending } ?: return null
        val key = session.lastPlayed ?: return null
        session.returnPending = false
        return ReturnTarget(key, session.view.rowId, session.loadedMedia.toList(),
            session.startCursor, session.cursor)
    }

    @Synchronized
    fun previousLoaded(token: Long, mediaKey: String): MediaItem? {
        val items = current?.takeIf { it.token == token }?.items ?: return null
        val index = items.indexOfFirst { it.ratingKey == mediaKey }
        return if (index > 0) items[index - 1] else null
    }

    @Synchronized
    fun nextLoaded(token: Long, mediaKey: String): MediaItem? {
        val items = current?.takeIf { it.token == token }?.items ?: return null
        val index = items.indexOfFirst { it.ratingKey == mediaKey }
        return if (index >= 0) items.getOrNull(index + 1) else null
    }

    /**
     * The successor in the originating page, loading a few more pages of that page at its
     * end. null is the real end: no more pages, the page/item cap, a FLOOD_WAIT or an error.
     */
    suspend fun next(token: Long, mediaKey: String): MediaItem? = pageMutex.withLock {
        repeat(MAX_CONTINUATION_PAGES) {
            nextLoaded(token, mediaKey)?.let { return@withLock it }
            val (section, cursor) = synchronized(this) {
                val session = current?.takeIf { s -> s.token == token && s.items.any { it.ratingKey == mediaKey } }
                    ?: return@withLock null
                if (session.pagesLoaded >= MAX_CONTINUATION_PAGES || session.items.size >= MAX_QUEUE_ITEMS ||
                    nowMs() < blockedUntilMs) return@withLock null
                (session.section ?: return@withLock null) to (session.cursor ?: return@withLock null)
            }
            val page = try {
                loadPage(section, cursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Respect FLOOD_WAIT: no further page request until it has passed.
                val wait = (failure as? TelegramFloodWaitException)?.seconds
                    ?: telegramFloodWaitSeconds(failure.message)
                if (wait != null) synchronized(this) {
                    blockedUntilMs = maxOf(blockedUntilMs, nowMs() + wait.coerceAtLeast(1) * 1_000L)
                }
                return@withLock null
            }
            appendPage(token, cursor, page)
        }
        nextLoaded(token, mediaKey)
    }

    @Synchronized
    internal fun appendPage(token: Long, requestCursor: String, page: TelegramMediaPage) {
        val session = current?.takeIf { it.token == token && it.cursor == requestCursor } ?: return
        val section = session.section ?: return
        session.pagesLoaded++
        session.cursor = page.nextCursor?.takeUnless { it == requestCursor }
        session.loadedMedia += page.items
        val known = session.items.mapTo(HashSet()) { it.ratingKey }
        session.items += telegramQueuePageItems(section, page.items, session.view)
            .filter { known.add(it.ratingKey) }
            .take((MAX_QUEUE_ITEMS - session.items.size).coerceAtLeast(0))
    }

    internal companion object {
        const val MAX_QUEUE_ITEMS = 500
        const val MAX_CONTINUATION_PAGES = 4
    }
}
