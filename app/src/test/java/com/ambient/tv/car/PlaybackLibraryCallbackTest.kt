@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.car

import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackLibraryCallbackTest {
    private class Source : PlaybackLibrarySource {
        val folders = mutableMapOf<String, List<CarAudioEntry>>()
        val entries = mutableMapOf<String, CarAudioEntry>()
        val playable = mutableSetOf<String>()
        var found = emptyList<CarAudioEntry>()
        val queries = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        var failure: Exception? = null
        var pendingEntry: CompletableDeferred<CarAudioEntry?>? = null
        var pendingSearch: CompletableDeferred<List<CarAudioEntry>>? = null
        var reads = 0

        override suspend fun children(parentId: String): List<CarAudioEntry> {
            reads++
            failure?.let { throw it }
            return folders[parentId].orEmpty()
        }
        override suspend fun entry(mediaId: String): CarAudioEntry? {
            reads++
            failure?.let { throw it }
            pendingEntry?.let { return it.await() }
            return entries[mediaId]
        }
        override suspend fun search(query: String): List<CarAudioEntry> {
            queries += query
            failure?.let { throw it }
            pendingSearch?.let { return it.await() }
            return found
        }
        override suspend fun canPlay(mediaId: String): Boolean {
            resolved += mediaId
            return mediaId in playable
        }
    }

    private val source = Source()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var approved = true
    private var selectionsRequested = 0
    private val callback = PlaybackLibraryCallback(source, scope, "Ambient TV",
        onSelectionRequested = { selectionsRequested++ }) { _, _ -> approved }
    private val player = object : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .build()

        override fun handleRelease() = Futures.immediateVoidFuture()
    }
    private val session = MediaLibrarySession.Builder(RuntimeEnvironment.getApplication(), player, callback)
        .setId("library-test")
        .build()
    private val browser = ControllerInfo.createTestOnlyControllerInfo(
        "test.browser", 1, 1, 1, 1, true, Bundle.EMPTY, true,
    )
    private val legacyBrowser = ControllerInfo.createTestOnlyControllerInfo(
        "test.legacy.browser", 2, 2, ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, true, Bundle.EMPTY, true,
    )

    @After fun tearDown() {
        scope.cancel()
        session.release()
        player.release()
    }

    @Test fun rootAndAuthorizationCompleteImmediatelyWithoutCatalogWork() {
        val connection = callback.onConnectAsync(session, browser)
        assertTrue(connection.isDone)
        assertTrue(connection.get().availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
        val root = callback.onGetLibraryRoot(session, browser, null)
        assertTrue(root.isDone)
        assertEquals(CarAudioIds.ROOT, root.get().value?.mediaId)
        assertEquals(true, root.get().value?.mediaMetadata?.isBrowsable)
        assertEquals(false, root.get().value?.mediaMetadata?.isPlayable)
        assertEquals(0, source.reads)

        approved = false
        assertFalse(callback.onConnectAsync(session, browser).get().isAccepted)
        assertEquals(SessionError.ERROR_PERMISSION_DENIED,
            callback.onGetLibraryRoot(session, browser, null).get().resultCode)
        assertEquals(0, source.reads)
    }

    @Test fun rootCategoriesRemainAddressableAfterBrowsing() {
        source.folders[CarAudioIds.ROOT] = listOf(CarAudioEntry(CarAudioIds.RADIO_ROOT, "Radio"))
        val children = callback.onGetChildren(session, browser, CarAudioIds.ROOT, 0, 10, null).get()
        assertEquals("Radio", children.value?.single()?.mediaMetadata?.title)
        val item = callback.onGetItem(session, browser, CarAudioIds.RADIO_ROOT).get()
        assertEquals(LibraryResult.RESULT_SUCCESS, item.resultCode)
        assertEquals("Radio", item.value?.mediaMetadata?.title)
        assertEquals(1, source.reads)
    }

    @Test fun legacySubscriptionDoesNotDependOnCachedParentItem() {
        source.folders[CarAudioIds.RADIO_ROOT] = listOf(CarAudioEntry(CarAudioIds.RADIO_ALL, "Stations"))
        val result = callback.onSubscribe(session, legacyBrowser, CarAudioIds.RADIO_ROOT, null).get()
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(1, source.reads)
    }

    @Test fun media3PaginationRetainsTheCatalogMoreFolder() {
        val entries = (0 until 40).map { CarAudioEntry(CarAudioIds.station("$it"), "Station $it", playable = true) }
        val more = CarAudioEntry(CarAudioIds.more(CarAudioIds.RADIO_ALL, 40), "More")
        source.folders[CarAudioIds.RADIO_ALL] = entries + more

        val first = callback.onGetChildren(session, browser, CarAudioIds.RADIO_ALL, 0, 40, null).get().value!!
        val second = callback.onGetChildren(session, browser, CarAudioIds.RADIO_ALL, 1, 40, null).get().value!!
        val legacy = callback.onGetChildren(session, legacyBrowser, CarAudioIds.RADIO_ALL, 0, Int.MAX_VALUE, null).get().value!!
        assertEquals(40, first.size)
        assertEquals(more.id, second.single().mediaId)
        assertEquals(true, second.single().mediaMetadata.isBrowsable)
        assertEquals(41, legacy.size)
        assertEquals(emptyList<MediaItem>(),
            callback.onGetChildren(session, browser, CarAudioIds.RADIO_ALL, Int.MAX_VALUE, Int.MAX_VALUE, null).get().value)
        assertEquals(SessionError.ERROR_BAD_VALUE,
            callback.onGetChildren(session, browser, CarAudioIds.RADIO_ALL, 0, 0, null).get().resultCode)
    }

    @Test fun aSelectionUsesCatalogMetadataAndDiscardsClientUris() {
        val id = CarAudioIds.station("station-id")
        source.entries[id] = CarAudioEntry(id, "Catalog station", "Country", playable = true)
        val requested = MediaItem.Builder()
            .setMediaId(id)
            .setUri("https://untrusted.test/stream?token=secret")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Client override").build())
            .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                .setMediaUri(android.net.Uri.parse("https://untrusted.test/other"))
                .setExtras(Bundle().apply { putString("private", "token") })
                .build())
            .build()

        val resolved = callback.onSetMediaItems(session, browser, listOf(requested), 0, 12_000L)
            .get(1, TimeUnit.SECONDS)
        val item = resolved.mediaItems.single()
        assertEquals(id, item.mediaId)
        assertEquals("Catalog station", item.mediaMetadata.title)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertNull(item.localConfiguration)
        assertNull(item.requestMetadata.mediaUri)
        assertNull(item.requestMetadata.extras)
        assertEquals(0, resolved.startIndex)
        assertEquals(12_000L, resolved.startPositionMs)
    }

    @Test fun voiceSearchSkipsFoldersAndUnavailableAudio() {
        val missing = CarAudioEntry(CarAudioIds.station("offline"), "Jazz offline", playable = true)
        val available = CarAudioEntry(CarAudioIds.spotifyTrack("jazz-track"), "Jazz", playable = true)
        source.found = listOf(CarAudioEntry(CarAudioIds.RADIO_ROOT, "Jazz category"), missing, available)
        source.playable += available.id
        val voice = MediaItem.Builder().setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setSearchQuery("  Jazz  ").build(),
        ).build()

        val item = callback.onAddMediaItems(session, browser, listOf(voice)).get().single()
        assertEquals(available.id, item.mediaId)
        assertNull(item.requestMetadata.searchQuery)
        assertEquals(listOf("Jazz"), source.queries)
        assertEquals(listOf(missing.id, available.id), source.resolved)
    }

    @Test fun coldStartSelectionCanResolveWithoutPriorBrowsing() {
        val id = CarAudioIds.spotifyTrack("saved-track")
        source.playable += id
        val item = callback.onAddMediaItems(session, browser,
            listOf(MediaItem.Builder().setMediaId(id).build())).get().single()
        assertEquals(id, item.mediaId)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertNull(item.localConfiguration)
    }

    @Test fun aNewSelectionCancelsAnOlderCatalogLookupBeforeItCanStart() {
        val firstId = CarAudioIds.station("slow")
        val secondId = CarAudioIds.station("newer")
        val delayed = CompletableDeferred<CarAudioEntry?>()
        source.pendingEntry = delayed
        val first = callback.onAddMediaItems(session, browser, listOf(MediaItem.Builder().setMediaId(firstId).build()))
        assertFalse(first.isDone)
        assertEquals(1, selectionsRequested)
        source.pendingEntry = null
        source.entries[secondId] = CarAudioEntry(secondId, "Newer", playable = true)
        val second = callback.onAddMediaItems(session, browser, listOf(MediaItem.Builder().setMediaId(secondId).build()))
        assertTrue(first.isCancelled)
        assertEquals(2, selectionsRequested)
        assertEquals(secondId, second.get().single().mediaId)
        delayed.complete(CarAudioEntry(firstId, "Slow", playable = true))
        assertTrue(first.isCancelled)
        // The adapter pauses the outgoing engine while preparing this resolved item. Its
        // already-completed resolution must remain successful when that pause is observed.
        callback.cancelPendingSelections()
        assertFalse(second.isCancelled)
    }

    @Test fun stopOrAlreadyPausedControlCancelsVoiceResolution() {
        val voice = MediaItem.Builder().setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setSearchQuery("Jazz").build(),
        ).build()
        for (command in listOf(Player.COMMAND_STOP, Player.COMMAND_PLAY_PAUSE)) {
            source.pendingSearch = CompletableDeferred()
            val request = callback.onAddMediaItems(session, browser, listOf(voice))
            assertFalse(request.isDone)
            callback.onPlayerInteractionFinished(session, browser,
                Player.Commands.Builder().add(command).build())
            assertTrue(request.isCancelled)
            source.pendingSearch!!.complete(emptyList())
            assertTrue(source.resolved.isEmpty())
        }
    }

    @Test fun rawUrisAndBrowseOnlyFoldersCannotStartPlayback() {
        for (requested in listOf(
            MediaItem.fromUri("https://untrusted.test/stream"),
            MediaItem.Builder().setMediaId(CarAudioIds.RADIO_ROOT).build(),
        )) {
            try {
                callback.onAddMediaItems(session, browser, listOf(requested)).get()
                fail("Unsupported selection must fail")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is IllegalArgumentException)
            }
        }
        assertEquals(0, source.reads)
        assertTrue(source.resolved.isEmpty())
    }

    @Test fun unsupportedPlaylistRequestsAreRejectedBeforeResolvingAnyItem() {
        val item = MediaItem.Builder().setMediaId(CarAudioIds.station("station")).build()
        for (items in listOf(emptyList(), listOf(item, item))) {
            try {
                callback.onAddMediaItems(session, browser, items).get()
                fail("The existing source queue accepts one selected item")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is IllegalArgumentException)
            }
        }
        assertEquals(0, source.reads)
    }

    @Test fun searchResultsRemainStableAcrossPagesAndRefreshOnNewSearch() {
        source.found = (0..2).map { CarAudioEntry(CarAudioIds.station("$it"), "Jazz $it", playable = true) }
        val first = callback.onGetSearchResult(session, browser, "Jazz", 0, 2, null).get().value!!
        val second = callback.onGetSearchResult(session, browser, "Jazz", 1, 2, null).get().value!!
        assertEquals(2, first.size)
        assertEquals(1, second.size)
        assertEquals(listOf("Jazz"), source.queries)

        source.found = emptyList()
        assertEquals(LibraryResult.RESULT_SUCCESS, callback.onSearch(session, browser, "Jazz", null).get().resultCode)
        assertTrue(callback.onGetSearchResult(session, browser, "Jazz", 0, 2, null).get().value!!.isEmpty())
        assertEquals(listOf("Jazz", "Jazz"), source.queries)
        callback.onDisconnected(session, browser)
        callback.onGetSearchResult(session, browser, "Jazz", 0, 2, null).get()
        assertEquals(3, source.queries.size)
    }

    @Test fun failedBrowseIsReportedAsAnErrorAndServiceCancellationSettlesFutures() {
        source.failure = IllegalStateException("Offline")
        val failure = callback.onGetChildren(session, browser, CarAudioIds.RADIO_ALL, 0, 40, null).get()
        assertEquals(SessionError.ERROR_IO, failure.resultCode)
        assertNull(failure.value)
        scope.cancel()
        assertTrue(callback.onGetChildren(session, browser, CarAudioIds.RADIO_ALL, 0, 40, null).isCancelled)
    }

    @Test fun itemConversionKeepsHttpsArtworkAndRequiredBrowseFlags() {
        val audio = CarAudioEntry(CarAudioIds.station("station"), "Station", "Country",
            "https://public.test/logo.png", playable = true).toMedia3Item()
        assertEquals("https://public.test/logo.png", audio.mediaMetadata.artworkUri.toString())
        assertNull(audio.localConfiguration)
        assertEquals(false, audio.mediaMetadata.isBrowsable)
        assertEquals(true, audio.mediaMetadata.isPlayable)
        val folder = CarAudioEntry(CarAudioIds.RADIO_ROOT, "Radio", artworkUrl = "http://private.test/art")
            .toMedia3Item()
        assertNull(folder.mediaMetadata.artworkUri)
        assertEquals(true, folder.mediaMetadata.isBrowsable)
        assertEquals(false, folder.mediaMetadata.isPlayable)
    }

    @Test fun liveRadioArtworkSurvivesBrowseItemAndSearchForBothCarProtocols() {
        val sourceId = "tvheadend:profile-scope"
        val id = CarAudioIds.liveAudio(sourceId, "radio-1")
        val artwork = CarAudioArtwork.uri(RuntimeEnvironment.getApplication(), id)
        val entry = CarAudioEntry(id, "News Radio", "101", artwork.toString(), playable = true)
        val parent = CarAudioIds.liveGroup(sourceId, "")
        source.entries[id] = entry
        source.folders[parent] = listOf(entry)
        source.found = listOf(entry)

        for (controller in listOf(browser, legacyBrowser)) {
            val item = callback.onGetItem(session, controller, id).get().value!!
            val children = callback.onGetChildren(session, controller, parent, 0, 40, null)
                .get().value!!.single()
            val search = callback.onGetSearchResult(session, controller, "News", 0, 40, null)
                .get().value!!.single()
            for (result in listOf(item, children, search)) {
                assertEquals(id, result.mediaId)
                assertEquals(artwork, result.mediaMetadata.artworkUri)
                assertEquals("News Radio", result.mediaMetadata.title)
                assertEquals(true, result.mediaMetadata.isPlayable)
                assertEquals(false, result.mediaMetadata.isBrowsable)
                assertNull(result.mediaMetadata.artworkData)
                assertNull(result.localConfiguration)
            }
        }
    }

    @Test fun liveRadioSelectionKeepsCatalogArtworkAndDiscardsClientImageUrls() {
        val id = CarAudioIds.liveAudio("iptv:playlist-scope", "radio-1")
        val artwork = CarAudioArtwork.uri(RuntimeEnvironment.getApplication(), id)
        source.entries[id] = CarAudioEntry(id, "Radio", artworkUrl = artwork.toString(), playable = true)
        val requested = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setArtworkUri(android.net.Uri.parse("https://untrusted.test/logo?ticket=secret"))
                .build())
            .build()

        val item = callback.onAddMediaItems(session, browser, listOf(requested)).get().single()
        assertEquals(artwork, item.mediaMetadata.artworkUri)
        assertEquals("Radio", item.mediaMetadata.title)
        assertNull(item.localConfiguration)
    }

    @Test fun itemConversionDoesNotPublishForeignOrMalformedContentUris() {
        val id = CarAudioIds.liveAudio("tvheadend:profile-scope", "radio-1")
        val artwork = CarAudioArtwork.uri(RuntimeEnvironment.getApplication(), id)
        for (uri in listOf(
            artwork.buildUpon().authority("foreign.app.caraudioart").build(),
            artwork.buildUpon().appendQueryParameter("url", "http://private.test/logo").build(),
            artwork.buildUpon().appendPath("extra").build(),
            android.net.Uri.parse("file:///data/user/0/com.ambient.tv/private-logo.png"),
        )) {
            val item = CarAudioEntry(id, "Radio", artworkUrl = uri.toString(), playable = true).toMedia3Item()
            assertNull(item.mediaMetadata.artworkUri)
        }
    }
}
