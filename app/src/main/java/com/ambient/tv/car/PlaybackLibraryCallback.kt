@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.car

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import timber.log.Timber

/** The library uses the same catalog for legacy Android Auto and Media3 browsers. */
internal interface PlaybackLibrarySource {
    suspend fun children(parentId: String): List<CarAudioEntry>
    suspend fun entry(mediaId: String): CarAudioEntry?
    suspend fun search(query: String): List<CarAudioEntry>
    suspend fun canPlay(mediaId: String): Boolean
}

/**
 * Browsing never creates a decoder or starts playback. Media items contain catalog IDs only;
 * the player's selection handler resolves those IDs using the existing source repositories.
 */
internal class PlaybackLibraryCallback(
    private val source: PlaybackLibrarySource,
    private val scope: CoroutineScope,
    private val rootTitle: String,
    private val onSelectionRequested: () -> Unit = {},
    private val acceptController: (MediaSession, ControllerInfo) -> Boolean,
) : MediaLibrarySession.Callback {
    constructor(
        catalog: CarAudioCatalog,
        scope: CoroutineScope,
        rootTitle: String,
        onSelectionRequested: () -> Unit = {},
        acceptController: (MediaSession, ControllerInfo) -> Boolean,
    ) : this(
        object : PlaybackLibrarySource {
            override suspend fun children(parentId: String) = catalog.children(parentId)
            override suspend fun entry(mediaId: String) = catalog.entry(mediaId)
            override suspend fun search(query: String) = catalog.search(query)
            override suspend fun canPlay(mediaId: String) = catalog.resolve(mediaId) != null
        },
        scope,
        rootTitle,
        onSelectionRequested,
        acceptController,
    )

    private val root = CarAudioEntry(CarAudioIds.ROOT, rootTitle).toMedia3Item()
    // The catalog's root categories aren't cached by CarAudioCatalog.entry(). Remember the
    // metadata we actually delivered so getItem also works for them and synthetic More folders.
    private val knownEntries = LinkedHashMap<String, CarAudioEntry>()
    private data class SearchKey(val browser: ControllerInfo, val query: String)
    private val searches = LinkedHashMap<SearchKey, List<MediaItem>>()
    private data class PendingSelection(val controller: ControllerInfo, val result: ListenableFuture<List<MediaItem>>)
    private var pendingSelection: PendingSelection? = null

    /** A legacy play-from request can still be resolving before the player sees any selection. */
    fun cancelPendingSelections() {
        val pending = pendingSelection
        pendingSelection = null
        pending?.result?.cancel(false)
    }

    override fun onConnectAsync(
        session: MediaSession,
        controller: ControllerInfo,
    ): ListenableFuture<ConnectionResult> = Futures.immediateFuture(
        if (acceptController(session, controller)) {
            // Explicitly allow the existing browser/search surface for approved controllers,
            // including the internal notification controller. Actual player commands are still
            // intersected with the adapter's commands (for example, radio cannot seek).
            ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
                .setAvailablePlayerCommands(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        } else ConnectionResult.reject(),
    )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // A legacy browser can block waiting for this result: never post it to the main thread.
        // Media3 publishes legacy SEARCH_SUPPORTED from the granted library search command.
        return Futures.immediateFuture(
            if (acceptController(session, browser)) LibraryResult.ofItem(root, null)
            else libraryError(SessionError.ERROR_PERMISSION_DENIED, params),
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryResult(params) {
        if (!CarAudioIds.isFolder(parentId) || !validLibraryPage(page, pageSize)) {
            return@libraryResult libraryError(SessionError.ERROR_BAD_VALUE, params)
        }
        val items = remember(source.children(parentId))
        // The catalog already bounds each folder and supplies explicit More folders. Media3's
        // page/pageSize slices that same folder; it must not advance a provider's cursor twice.
        LibraryResult.ofItemList(libraryPage(items, page, pageSize), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (mediaId == CarAudioIds.ROOT) return Futures.immediateFuture(LibraryResult.ofItem(root, null))
        return libraryResult(null) {
            val entry = knownEntries[mediaId] ?: source.entry(mediaId)
            if (entry == null) libraryError(SessionError.ERROR_BAD_VALUE, null)
            else LibraryResult.ofItem(remember(listOf(entry)).single(), null)
        }
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = libraryResult(params) {
        if (!CarAudioIds.isFolder(parentId)) {
            return@libraryResult libraryError(SessionError.ERROR_BAD_VALUE, params)
        }
        // The default callback requires getItem(parent) to succeed, which isn't guaranteed for
        // catalog folders after a service restart. Validate their opaque IDs and load normally.
        val items = remember(source.children(parentId))
        if (browser.controllerVersion != ControllerInfo.LEGACY_CONTROLLER_VERSION) {
            session.notifyChildrenChanged(browser, parentId, items.size, params)
        }
        LibraryResult.ofVoid(params)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = libraryResult(params) {
        val key = SearchKey(browser, normalizeLibraryQuery(query))
        val items = search(key, refresh = true)
        session.notifySearchResultChanged(browser, query, items.size, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryResult(params) {
        if (!validLibraryPage(page, pageSize)) {
            return@libraryResult libraryError(SessionError.ERROR_BAD_VALUE, params)
        }
        // Legacy clients can request results without a preceding onSearch callback.
        val items = search(SearchKey(browser, normalizeLibraryQuery(query)), refresh = false)
        LibraryResult.ofItemList(libraryPage(items, page, pageSize), params)
    }

    override fun onDisconnected(session: MediaSession, controller: ControllerInfo) {
        searches.keys.removeAll { it.browser == controller }
        if (pendingSelection?.controller == controller) cancelPendingSelections()
    }

    override fun onPlayerInteractionFinished(
        session: MediaSession,
        controllerInfo: ControllerInfo,
        playerCommands: Player.Commands,
    ) {
        // The player can defer transport actions while a source is being selected. Cancel at
        // the session boundary too, so a pending legacy voice lookup cannot restart audio.
        if (playerCommands.contains(Player.COMMAND_STOP) ||
            (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) && !session.player.playWhenReady)) {
            cancelPendingSelections()
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        cancelPendingSelections()
        // Cancel an older decoder selection before this request starts resolving its catalog
        // ID or voice query; waiting until the new item reaches the player would be too late.
        onSelectionRequested()
        val result = scope.libraryFuture {
            require(mediaItems.size == 1) { "Select one audio item" }
            mediaItems.map { requested ->
                val query = normalizeLibraryQuery(requested.requestMetadata.searchQuery.orEmpty())
                val entry = if (query.isNotEmpty()) {
                    // Keep the previous voice behavior: skip unavailable/non-audio matches until
                    // the first playable result. Starting the selected decoder remains the player's job.
                    source.search(query).firstOrNull { candidate ->
                        candidate.playable && isCarAudioPlayableId(candidate.id) && source.canPlay(candidate.id)
                    }
                } else {
                    require(isCarAudioPlayableId(requested.mediaId)) { "Unknown audio media ID" }
                    val known = knownEntries[requested.mediaId] ?: source.entry(requested.mediaId)
                    when {
                        known != null -> known.takeIf { it.playable }
                        // Spotify and saved external selections can resolve after a cold start,
                        // before the browser has requested their containing folder.
                        source.canPlay(requested.mediaId) -> CarAudioEntry(requested.mediaId, rootTitle, playable = true)
                        else -> null
                    }
                }
                requireNotNull(entry) { "Audio item is unavailable" }.toMedia3Item()
                // A fresh item deliberately drops client-provided URIs, extras and searchQuery.
                // The default onSetMediaItems preserves its requested index/position and delegates
                // to this method; the adapter resolves only this canonical catalog ID.
            }
        }
        pendingSelection = PendingSelection(controller, result)
        return result
    }

    private suspend fun search(key: SearchKey, refresh: Boolean): List<MediaItem> {
        if (!refresh) searches[key]?.let { return it }
        val items = if (key.query.isEmpty()) emptyList() else remember(source.search(key.query))
        searches[key] = items
        while (searches.size > MAX_SEARCHES) searches.remove(searches.keys.first())
        return items
    }

    private fun remember(entries: List<CarAudioEntry>): List<MediaItem> {
        entries.forEach { knownEntries[it.id] = it }
        while (knownEntries.size > MAX_KNOWN_ITEMS) knownEntries.remove(knownEntries.keys.first())
        return entries.map { it.toMedia3Item() }
    }

    private fun <T : Any> libraryResult(
        params: LibraryParams?,
        block: suspend () -> LibraryResult<T>,
    ): ListenableFuture<LibraryResult<T>> = scope.libraryFuture {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Media library request failed")
            libraryError(SessionError.ERROR_IO, params)
        }
    }

    private companion object {
        const val MAX_KNOWN_ITEMS = 500
        const val MAX_SEARCHES = 16
    }
}

private fun <T : Any> libraryError(code: Int, params: LibraryParams?): LibraryResult<T> {
    val error = SessionError(code, "Media library request failed")
    return if (params == null) LibraryResult.ofError<T>(error) else LibraryResult.ofError<T>(error, params)
}

/** Cancellation belongs to both sides: a disconnected caller or a destroyed service ends work. */
internal fun <T> CoroutineScope.libraryFuture(block: suspend () -> T): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    val job = launch(start = CoroutineStart.LAZY) {
        try {
            future.set(block())
        } catch (cancelled: CancellationException) {
            future.cancel(false)
            throw cancelled
        } catch (error: Exception) {
            future.setException(error)
        }
    }
    future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
    job.invokeOnCompletion { error ->
        // A cancelled scope can prevent a lazy child from ever entering its body.
        if (error is CancellationException) future.cancel(false)
        else if (error != null) future.setException(error)
    }
    job.start()
    return future
}

internal fun normalizeLibraryQuery(query: String): String = query.trim().take(120)

internal fun isCarAudioPlayableId(id: String): Boolean = id.length <= 2_048 &&
    (CarAudioIds.stationId(id) != null || CarAudioIds.spotifyTrackId(id) != null ||
        CarAudioIds.musicItemId(id) != null || CarAudioIds.liveAudioSpec(id) != null ||
        CarAudioIds.telegramItemId(id) != null)

internal fun validLibraryPage(page: Int, pageSize: Int) = page >= 0 && pageSize > 0

/** Long arithmetic keeps oversized legacy page requests from overflowing back to the first page. */
internal fun <T> libraryPage(items: List<T>, page: Int, pageSize: Int): List<T> {
    require(validLibraryPage(page, pageSize))
    val start = page.toLong() * pageSize.toLong()
    if (start >= items.size) return emptyList()
    val end = (start + pageSize).coerceAtMost(items.size.toLong())
    return items.subList(start.toInt(), end.toInt())
}
