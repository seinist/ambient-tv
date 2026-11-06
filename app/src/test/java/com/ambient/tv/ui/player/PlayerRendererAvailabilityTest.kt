@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Application
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TitleVideoSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlayerRendererAvailabilityTest {
    private lateinit var application: Application
    private lateinit var preferences: AppPreferences
    private val metadata = PlaybackMetadata("renderer-test/current", "Current video", source = "plex", mediaId = "renderer-test")

    private class Clock : PlaybackHandle {
        override fun status() = PlaybackStatus(positionMs = 30_000L, durationMs = 300_000L)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
    }

    @Before fun setUp() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("test", 0).edit().clear().commit()
        preferences = videoImagePreferences(application)
        preferences.playbackBackendKey = "vlc"
        preferences.titleVideoSettings = mapOf("plex:renderer-test" to TitleVideoSettings(rendererKey = "mpv"))
        TitleVideoSettingsStore.endSession()
    }

    @After fun tearDown() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    @Test fun mimeAndSelectedCodecIdentifyDolbyVisionWithoutServerMetadata() {
        assertTrue(rendererFormatIsDolbyVision(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
        listOf("dvhe.07.06", "dvh1.08.06", "dvav.09.09", "dav1.10.09").forEach { codec ->
            assertTrue(codec, rendererFormatIsDolbyVision(Format.Builder().setCodecs(codec).build()))
        }
        assertFalse(rendererFormatIsDolbyVision(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L153.B0").build()))
        assertFalse(rendererFormatIsDolbyVision(null))
    }

    @Test fun librarySourceProtectionSurvivesDecoderReportingOnlyHdr10BaseLayer() {
        val availability = currentPlayerRendererAvailability(metadata.copy(
            dolbyVisionSource = true, handheldHdrStandard = HdrStandard.HDR10,
        ), mpvAvailable = true)
        assertEquals(setOf("vlc", "mpv"), availability.disabledKeys)
    }

    @Test fun liveMetadataAndRuntimeOnlyMonogramOrYouTubeUseTheSameRestriction() {
        val live = currentPlayerRendererAvailability(metadata.copy(source = "tvheadend", isLive = true,
            handheldHdrStandard = HdrStandard.DOLBY_VISION), mpvAvailable = true)
        assertFalse(live.canSelect("vlc"))
        listOf("telegram", "youtube").forEach { source ->
            val availability = currentPlayerRendererAvailability(metadata.copy(source = source),
                observedDolbyVision = true, mpvAvailable = true)
            assertEquals(live.disabledKeys, availability.disabledKeys)
            assertTrue(availability.canSelect("media3"))
        }
    }

    @Test fun lateSourceDetectionRejectsOldMenuChoiceBeforeChangingPreferencesOrRenderer() {
        BackgroundPlaybackSession.acquire(application, metadata, "fixture", Any()) { Clock() }
        // The open menu still holds its original metadata; the session has learned the source type.
        BackgroundPlaybackSession.refreshMetadata(metadata.copy(dolbyVisionSource = true))
        var selected = false
        assertFalse(selectAvailablePlayerRenderer(application, metadata, "vlc") { _, _ -> selected = true })
        assertFalse(selected)
        assertEquals("vlc", preferences.playbackBackendKey)
        assertEquals("mpv", preferences.titleVideoSettings["plex:renderer-test"]?.rendererKey)
        assertEquals("fixture", BackgroundPlaybackSession.snapshot.value.backend)
    }

    @Test fun convertedSdrSourceReplacesStaleDolbyVisionFactsForTheSameTitle() {
        preferences.playbackBackendKey = "mpv"
        val original = metadata.copy(dolbyVisionSource = true, handheldHdrStandard = HdrStandard.DOLBY_VISION)
        BackgroundPlaybackSession.acquire(application, original, "fixture", Any()) { Clock() }
        BackgroundPlaybackSession.refreshMetadata(metadata)
        val availability = currentPlayerRendererAvailability(original, mpvAvailable = true)
        assertTrue(availability.canSelect("vlc"))
        assertTrue(availability.canSelect("mpv"))
        var selected: String? = null
        assertTrue(selectAvailablePlayerRenderer(application, original, "vlc") { key, _ -> selected = key })
        assertEquals("vlc", selected)
        assertEquals("vlc", preferences.titleVideoSettings["plex:renderer-test"]?.rendererKey)
        assertEquals("mpv", preferences.playbackBackendKey)
    }

    @Test fun decoderReportingHdr10DoesNotRemoveTheCurrentSourcesDolbyVisionRestriction() {
        BackgroundPlaybackSession.acquire(application, metadata, "fixture", Any()) { Clock() }
        BackgroundPlaybackSession.refreshMetadata(metadata.copy(dolbyVisionSource = true, handheldHdrStandard = HdrStandard.HDR10))
        val availability = currentPlayerRendererAvailability(metadata, mpvAvailable = true)
        assertFalse(availability.canSelect("vlc"))
        assertFalse(availability.canSelect("mpv"))
    }

    @Test fun panelFromPreviousTitleCannotWritePreferencesDuringNewPlayback() {
        BackgroundPlaybackSession.acquire(application, metadata.copy(route = "renderer-test/next", mediaId = "next"),
            "fixture", Any()) { Clock() }
        var selected = false
        assertFalse(selectAvailablePlayerRenderer(application, metadata, "media3") { _, _ -> selected = true })
        assertFalse(selected)
        assertEquals("mpv", preferences.titleVideoSettings["plex:renderer-test"]?.rendererKey)
        assertFalse("plex:next" in preferences.titleVideoSettings)
    }

    @Test fun audioOnlyPlaybackDoesNotInheritAVideoRestriction() {
        val availability = currentPlayerRendererAvailability(metadata.copy(hasVideo = false, dolbyVisionSource = true),
            observedDolbyVision = true, mpvAvailable = true)
        assertTrue(availability.canSelect("vlc"))
        assertTrue(availability.canSelect("mpv"))
    }
}
