package com.ambient.tv.car

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.net.Uri
import com.ambient.tv.BuildConfig
import com.ambient.tv.livetv.LiveTvCapabilities
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvPlaybackSource
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording
import com.ambient.tv.livetv.LiveTvRepository
import com.ambient.tv.livetv.LiveTvSource
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarAudioArtworkTest {
    private val context = RuntimeEnvironment.getApplication<Application>()
    private val id = CarAudioIds.liveAudio("tvheadend:profile-scope", "radio-1")

    private class Repository(
        val stations: List<LiveTvChannel>,
        val headers: Map<String, String> = emptyMap(),
        override val source: LiveTvSource = LiveTvSource("tvheadend:profile-scope", "tvheadend", "TVHeadend"),
    ) : LiveTvRepository {
        var channelReads = 0
        var authReads = 0
        override fun authHeaders(): Map<String, String> {
            authReads++
            return headers
        }
        override suspend fun channels(force: Boolean): List<LiveTvChannel> {
            channelReads++
            return stations
        }
        override suspend fun capabilities(force: Boolean): LiveTvCapabilities = error("Artwork only needs channels")
        override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> =
            error("Artwork must not load programme information")
        override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource =
            error("Artwork must not start a stream")
        override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource =
            error("Artwork must not start a recording")
    }

    private fun radio(url: String? = "http://tvheadend.test:9981/imagecache/42?ticket=short-lived") =
        LiveTvChannel("radio-1", "News Radio", 101, url, kind = LiveTvChannelKind.RADIO)

    private fun uncheckedUri(mediaId: String): Uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.caraudioart")
        .appendPath("live")
        .appendPath(mediaId)
        .build()

    private fun provider() = CarAudioArtworkProvider().apply {
        attachInfo(context, ProviderInfo().apply {
            authority = "${context.packageName}.caraudioart"
            name = CarAudioArtworkProvider::class.java.name
            exported = true
        })
    }

    @Test fun authenticatedTvheadendHttpLogoRetainsTheSourceCredentials() = runBlocking {
        val channel = radio()
        val headers = mapOf("Authorization" to "Basic test-credential")
        val repository = Repository(listOf(channel), headers)
        val request = requireNotNull(carLiveArtworkRequest(id, listOf(repository)))

        assertEquals(channel.iconUrl, request.url)
        assertEquals(headers, request.headers)
        assertEquals(1, repository.channelReads)
        assertEquals(1, repository.authReads)
        // The public URI contains only the opaque catalog ID, never the HTTP logo or its ticket.
        val published = CarAudioArtwork.uri(context, id).toString()
        assertFalse(published.contains("tvheadend.test"))
        assertFalse(published.contains("ticket"))
        assertFalse(published.contains("test-credential"))
    }

    @Test fun matchingChannelIdsNeverResolveThroughAnotherSource() = runBlocking {
        val other = Repository(listOf(radio("https://other.test/logo.png")),
            mapOf("Authorization" to "other-credential"),
            LiveTvSource("tvheadend:other-scope", "tvheadend", "Other server"))
        val selected = Repository(listOf(radio()), mapOf("Authorization" to "selected-credential"))

        val request = requireNotNull(carLiveArtworkRequest(id, listOf(other, selected)))
        assertEquals(selected.headers, request.headers)
        assertEquals(selected.stations.single().iconUrl, request.url)
        assertEquals(0, other.channelReads)
        assertEquals(0, other.authReads)
        assertNull(carLiveArtworkRequest(id, listOf(other)))
        assertEquals(0, other.channelReads)
    }

    @Test fun iptvArtworkUsesThePlaylistHeaders() = runBlocking {
        val repository = Repository(listOf(radio("http://playlist.test/logo.png")),
            mapOf("User-Agent" to "Playlist player", "Referer" to "https://playlist.test/"),
            LiveTvSource("iptv:playlist-scope", "iptv", "Playlist"))
        val mediaId = CarAudioIds.liveAudio(repository.source.id, "radio-1")
        val request = requireNotNull(carLiveArtworkRequest(mediaId, listOf(repository)))
        assertEquals("http://playlist.test/logo.png", request.url)
        assertEquals(repository.headers, request.headers)
    }

    @Test fun repeatedRadioRowCanSupplyTheMissingLogo() = runBlocking {
        val withLogo = radio()
        val repository = Repository(listOf(radio(null), withLogo),
            mapOf("Authorization" to "Basic test-credential"))
        val request = requireNotNull(carLiveArtworkRequest(id, listOf(repository)))
        assertEquals(withLogo.iconUrl, request.url)
        assertEquals(repository.headers, request.headers)
    }

    @Test fun televisionUnknownAndMissingChannelsDoNotExposeArtworkOrCredentials() = runBlocking {
        for (channels in listOf(
            listOf(radio().copy(kind = LiveTvChannelKind.TV)),
            listOf(radio().copy(kind = LiveTvChannelKind.UNKNOWN)),
            listOf(radio().copy(uuid = "different-radio")),
            emptyList(),
        )) {
            val repository = Repository(channels, mapOf("Authorization" to "private"))
            assertNull(carLiveArtworkRequest(id, listOf(repository)))
            assertEquals(0, repository.authReads)
        }
        val unsupported = Repository(listOf(radio()),
            source = LiveTvSource("tvheadend:profile-scope", "plex", "Other provider"))
        assertNull(carLiveArtworkRequest(id, listOf(unsupported)))
        assertEquals(0, unsupported.channelReads)
        assertEquals(0, unsupported.authReads)
    }

    @Test fun emptyAndNonNetworkLogosAreNotLoadedThroughTheExportedProvider() = runBlocking {
        for (url in listOf(null, "", " ", "file:///data/private.png",
            "content://other.provider/private.png", "ftp://server/logo.png", "data:image/png;base64,abcd")) {
            val repository = Repository(listOf(radio(url)), mapOf("Authorization" to "private"))
            assertNull(carLiveArtworkRequest(id, listOf(repository)))
            assertEquals(0, repository.authReads)
        }
    }

    @Test fun liveRadioIdsRoundTripWithoutPublishingServerAddresses() {
        for (source in listOf("tvheadend:profile-scope", "iptv:playlist-scope")) {
            val mediaId = CarAudioIds.liveAudio(source, "radio / Süd #1")
            val uri = CarAudioArtwork.uri(context, mediaId)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.caraudioart", uri.authority)
            assertEquals(listOf("live", mediaId), uri.pathSegments)
            assertEquals(mediaId, CarAudioArtwork.mediaId(uri, context.packageName))
            assertNull(uri.query)
            assertNull(uri.fragment)
        }
    }

    @Test fun packageVariantsUseTheirOwnProviderAuthority() {
        val variantPackage = "${BuildConfig.APPLICATION_ID}.audit"
        val variant = object : ContextWrapper(context) {
            override fun getPackageName() = variantPackage
        }
        val uri = CarAudioArtwork.uri(variant, id)
        assertEquals("$variantPackage.caraudioart", uri.authority)
        assertEquals(id, CarAudioArtwork.mediaId(uri, variantPackage))
        assertNull(CarAudioArtwork.mediaId(uri, BuildConfig.APPLICATION_ID))
    }

    @Test fun foldersOtherProvidersAndMalformedIdsCannotBecomeArtworkRequests() {
        val invalidIds = listOf(
            CarAudioIds.RADIO_ROOT,
            CarAudioIds.liveGroup("tvheadend:profile-scope", "News"),
            CarAudioIds.station("radio-browser-station"),
            CarAudioIds.musicItem("track-1"),
            CarAudioIds.liveAudio("plex:profile", "radio-1"),
            CarAudioIds.liveAudio("tvheadend:", "radio-1"),
            CarAudioIds.liveAudio("iptv:profile", ""),
            CarAudioIds.liveAudio("tvheadend:profile", "radio-1\nextra"),
            CarAudioIds.liveAudio("tvheadend:profile", "x".repeat(1024)),
            "car:live:audio:invalid%base64",
        )
        for (mediaId in invalidIds) {
            assertThrows(IllegalArgumentException::class.java) { CarAudioArtwork.uri(context, mediaId) }
            assertNull(CarAudioArtwork.mediaId(uncheckedUri(mediaId), context.packageName))
        }
    }

    @Test fun foreignAuthoritiesPathsAndUriParametersAreRejectedBeforeLoading() {
        val valid = CarAudioArtwork.uri(context, id)
        val invalidUris = listOf(
            valid.buildUpon().scheme("https").build(),
            valid.buildUpon().authority("foreign.app.caraudioart").build(),
            valid.buildUpon().authority("${context.packageName}.caraudioart.evil").build(),
            valid.buildUpon().path("/live").build(),
            valid.buildUpon().path("/other/$id").build(),
            valid.buildUpon().appendPath("extra").build(),
            valid.buildUpon().appendQueryParameter("url", "http://private.test/image?ticket=secret").build(),
            valid.buildUpon().fragment("private").build(),
            uncheckedUri("../../private/image"),
        )
        val provider = provider()
        for (uri in invalidUris) {
            assertNull(CarAudioArtwork.mediaId(uri, context.packageName))
            assertNull(provider.getType(uri))
            // The plain test Application has no Hilt dependencies. Invalid requests must be
            // rejected before resolving repositories or attempting any image/network work.
            assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "r") }
        }
    }

    @Test fun providerAdvertisesPngAndRejectsAllWriteOperations() {
        val provider = provider()
        val uri = CarAudioArtwork.uri(context, id)
        assertEquals("image/png", provider.getType(uri))
        assertNull(provider.query(uri, null, null, null, null))
        for (mode in listOf("w", "wa", "rw", "rwt")) {
            assertThrows(SecurityException::class.java) { provider.openFile(uri, mode) }
        }
        assertThrows(SecurityException::class.java) { provider.insert(uri, null) }
        assertThrows(SecurityException::class.java) { provider.update(uri, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.delete(uri, null, null) }
    }
}
