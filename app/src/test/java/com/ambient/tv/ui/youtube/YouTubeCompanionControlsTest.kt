package com.ambient.tv.ui.youtube

import com.ambient.tv.companion.CompanionOption
import com.ambient.tv.companion.CompanionPlaybackControls
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubePlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCompanionControlsTest {
    private fun ready() = YouTubePlayerState(
        loading = false,
        playback = YouTubePlayback(videoId = "current", title = "Trailer", uri = "fixture://trailer"),
        nextVideo = YouTubeItem("next", "Next trailer"),
        previousVideoId = "previous",
    )

    @Test fun media3TrailerRemoteTransportUsesTheCurrentQueueAndKeepsNativeTrackFallback() {
        val actions = mutableListOf<String>()
        val controls = requireNotNull(youtubeCompanionControls(ready(), isTrailer = true, streamControls = null,
            onNext = { actions += "next:$it" }, onPrevious = { actions += "previous:$it" }))
        requireNotNull(controls.next).invoke()
        requireNotNull(controls.previous).invoke()
        assertEquals(listOf("next:next", "previous:previous"), actions)
        assertTrue(controls.quality.isEmpty())
        assertNull(controls.audio)
        assertNull(controls.chooseAudio)
    }

    @Test fun trailerTransportSharesTheNativeRenderersQualityAndAudioLease() {
        val actions = mutableListOf<String>()
        val stream = CompanionPlaybackControls.SourceControls(
            quality = listOf(CompanionOption("1080", "1080p", true)),
            chooseQuality = { actions += "quality:$it" },
            audio = listOf(CompanionOption("de", "Deutsch", true)),
            chooseAudio = { actions += "audio:$it" },
        )
        val controls = requireNotNull(youtubeCompanionControls(ready(), isTrailer = true, streamControls = stream,
            onNext = { actions += "next:$it" }, onPrevious = { actions += "previous:$it" }))
        assertSame(stream.quality, controls.quality)
        assertSame(stream.audio, controls.audio)
        requireNotNull(controls.chooseQuality).invoke("720")
        requireNotNull(controls.chooseAudio).invoke("en")
        requireNotNull(controls.next).invoke()
        assertEquals(listOf("quality:720", "audio:en", "next:next"), actions)
    }

    @Test fun aLoadingHandoffConsumesRepeatedRemoteCommandsWithoutLaunchingAnotherRoute() {
        val controls = requireNotNull(youtubeCompanionControls(ready().copy(loading = true),
            isTrailer = true, streamControls = null,
            onNext = { error("Next is already loading") }, onPrevious = { error("Next is already loading") }))
        // CompanionManager falls back to a new destination only when these callbacks are null.
        requireNotNull(controls.next).invoke()
        requireNotNull(controls.previous).invoke()
    }

    @Test fun liveAndStandaloneTrailersNeverPublishUnavailableRemoteActions() {
        val ready = ready()
        val unavailable = listOf(
            ready.copy(playback = ready.playback?.copy(isLive = true)),
            ready.copy(playback = null),
            ready.copy(nextVideo = null, previousVideoId = null),
        )
        for (state in unavailable) {
            val controls = requireNotNull(youtubeCompanionControls(state, isTrailer = true, streamControls = null,
                onNext = { error("Unavailable Next") }, onPrevious = { error("Unavailable Previous") }))
            assertNull(controls.next)
            assertNull(controls.previous)
        }
        val last = requireNotNull(youtubeCompanionControls(ready.copy(nextVideo = null), isTrailer = true,
            streamControls = null, onNext = { error("No successor") }, onPrevious = {}))
        assertNull(last.next)
        requireNotNull(last.previous).invoke()
    }

    @Test fun normalYoutubeKeepsItsExistingCompanionControls() {
        val stream = CompanionPlaybackControls.SourceControls(audio = emptyList())
        assertSame(stream, youtubeCompanionControls(ready(), isTrailer = false, streamControls = stream,
            onNext = {}, onPrevious = {}))
        assertNull(youtubeCompanionControls(ready(), isTrailer = false, streamControls = null,
            onNext = {}, onPrevious = {}))
    }
}
