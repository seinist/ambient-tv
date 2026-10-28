package com.ambient.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.domain.playback.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NerdStatsSettingRowsTest {
    private val labels = NerdSettingLabels()
    private fun audio(options: AudioOptions, suspended: Boolean = false, automatic: Boolean = false, backend: String = "mpv") =
        NerdAudioSettings("TV", options, titleDelay = false, nightAutomatic = automatic, dspSuspended = suspended,
            supportsNight = true, backend = backend, downmix = null)

    @Test fun videoRowsShowHdrModePictureAndSpeed() {
        val (video, _) = nerdSettingRows(labels, "HDR10", PlaybackMode.TRANSCODE,
            VideoPresentation(resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM, zoom = 1.25f), 1.5f, null)
        assertEquals(listOf("HDR10", "Transcode", "Fill · 125 %", "1.50×"), video.map { it.value })
        assertTrue(video.first { it.label == labels.streamMode }.warn)
    }

    @Test fun normalSpeedAndFitStayQuiet() {
        val (video, _) = nerdSettingRows(labels, null, PlaybackMode.DIRECT_PLAY, VideoPresentation(), 1f, null)
        assertEquals(listOf("Direct play", "Fit"), video.map { it.value })
    }

    @Test fun soundProcessingListsActiveStages() {
        val (_, sound) = nerdSettingRows(labels, null, null, VideoPresentation(), null,
            audio(AudioOptions(eqEnabled = true, preampDb = -3, dialogue = 2, night = true, delayMs = 120), automatic = true))
        assertEquals("+120 ms", sound.first { it.label == labels.delay }.value)
        assertEquals("EQ -3 dB · Dialogue +2 · Night mode (automatic)", sound.first { it.label == labels.sound }.value)
    }

    @Test fun bitstreamSuspendsProcessingAndPlainProfileSaysOff() {
        val (_, suspended) = nerdSettingRows(labels, null, null, VideoPresentation(), null, audio(AudioOptions(night = true), suspended = true))
        assertEquals(labels.suspended, suspended.first { it.label == labels.sound }.value)
        val (_, plain) = nerdSettingRows(labels, null, null, VideoPresentation(), null, audio(AudioOptions(), backend = "vlc"))
        assertEquals(labels.off, plain.first { it.label == labels.sound }.value)
    }
}
