package com.ambient.tv.ui.youtube

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import com.ambient.tv.util.createAndroidComposeRule
import com.ambient.tv.youtube.YouTubePlayback
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YouTubeMedia3PreparationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun playback(id: String) = YouTubePlayback(id, "Video $id", uri = "https://video.test/$id")

    @Test fun nextUpWaitsForItsOwnManifestWithoutPublishingThePreviousVideo() {
        val source = mutableStateOf(playback("first"))
        var prepared: YouTubePlayback? = null
        rule.setContent {
            val current = rememberYouTubeMedia3Playback(source.value).value
            SideEffect { prepared = current }
        }
        val nextManifest = CompletableDeferred<String?>()
        rule.runOnIdle {
            assertEquals("first", prepared?.videoId)
            source.value = playback("next").copy(deferredDash = nextManifest)
        }
        rule.runOnIdle {
            assertNull("The outgoing source must not be acquired under the next video's metadata", prepared)
            nextManifest.complete("file:///next.mpd")
        }
        rule.runOnIdle {
            assertEquals("next", prepared?.videoId)
            assertEquals("file:///next.mpd", prepared?.uri)
        }
    }

    @Test fun cancelledPreparationCannotPublishAnOutgoingFallback() {
        val firstManifest = CompletableDeferred<String?>()
        val nextManifest = CompletableDeferred<String?>()
        val source = mutableStateOf(playback("first").copy(deferredDash = firstManifest))
        var prepared: YouTubePlayback? = null
        rule.setContent {
            val current = rememberYouTubeMedia3Playback(source.value).value
            SideEffect { prepared = current }
        }
        rule.runOnIdle { source.value = playback("next").copy(deferredDash = nextManifest) }
        rule.runOnIdle {
            assertNull(prepared)
            // Completion of a retired producer has no authority over the incoming item.
            firstManifest.complete("file:///first.mpd")
        }
        rule.runOnIdle {
            assertNull(prepared)
            nextManifest.complete("file:///next.mpd")
        }
        rule.runOnIdle { assertEquals("next", prepared?.videoId) }
    }

    @Test fun aFailedCurrentManifestUsesOnlyItsOwnFallback() {
        val manifest = CompletableDeferred<String?>()
        val source = playback("current").copy(deferredDash = manifest)
        var prepared: YouTubePlayback? = null
        rule.setContent {
            val current = rememberYouTubeMedia3Playback(source).value
            SideEffect { prepared = current }
        }
        rule.runOnIdle { manifest.completeExceptionally(IllegalStateException("Manifest unavailable")) }
        rule.runOnIdle {
            assertEquals("current", prepared?.videoId)
            assertEquals(source.uri, prepared?.uri)
            assertNull(prepared?.deferredDash)
        }
    }

    @Test fun metadataEnrichmentDoesNotTurnProducerCancellationIntoAFallback() {
        val manifest = CompletableDeferred<String?>()
        val source = mutableStateOf(playback("current").copy(deferredDash = manifest))
        var prepared: YouTubePlayback? = null
        rule.setContent {
            val current = rememberYouTubeMedia3Playback(source.value).value
            SideEffect { prepared = current }
        }
        rule.runOnIdle { source.value = source.value.copy(title = "Enriched title") }
        rule.runOnIdle {
            assertNull("The manifest is still pending; cancelling the old metadata producer is not a failure", prepared)
            manifest.complete("file:///current.mpd")
        }
        rule.runOnIdle {
            assertEquals("file:///current.mpd", prepared?.uri)
            assertEquals("Enriched title", prepared?.title)
        }
    }

    @Test fun failedManifestNeverOpensTheNativeVideoOnlyFallback() {
        val manifest = CompletableDeferred<String?>()
        val video = "https://video.test/video-only"
        val source = playback("current").copy(uri = video, adaptiveVideoUri = video,
            adaptiveAudioUri = "https://video.test/audio-only", deferredDash = manifest)
        var prepared: YouTubePlayback? = null
        rule.setContent {
            val current = rememberYouTubeMedia3Playback(source).value
            SideEffect { prepared = current }
        }
        rule.runOnIdle { manifest.complete(null) }
        rule.runOnIdle {
            assertEquals("", prepared?.uri)
            assertNull(prepared?.deferredDash)
        }
    }

}
