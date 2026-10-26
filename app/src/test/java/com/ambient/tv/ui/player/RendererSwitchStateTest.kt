package com.ambient.tv.ui.player

import com.ambient.tv.ui.player.backend.MPV_MEDIA_INFO_SETTLE_MS
import com.ambient.tv.ui.player.backend.MPV_NERD_WARMUP_MS
import com.ambient.tv.ui.player.backend.MpvPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VLC_STREAM_INFO_WARMUP_MS
import com.ambient.tv.ui.player.backend.VlcPlaybackSnapshot
import com.ambient.tv.ui.player.backend.mpvMediaInfoChanged
import com.ambient.tv.ui.player.backend.mpvNerdPollIntervalMs
import com.ambient.tv.ui.player.backend.mpvRefreshMediaInfo
import com.ambient.tv.ui.player.backend.mpvSnapshotTickMs
import com.ambient.tv.ui.player.backend.vlcStreamInfoIntervalMs
import com.ambient.tv.ui.player.backend.vlcStreamInfoWarmup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSwitchStateTest {
    @Test fun mpvPanelKeepsVideoUntilAudioOnlyIsKnown() {
        // Fresh engine after a switch: no codec yet, but nothing says audio-only.
        assertTrue(mpvPanelHasVideo(videoCodec = null, audioOnly = false))
        assertTrue(mpvPanelHasVideo(videoCodec = "", audioOnly = false))
        assertTrue(mpvPanelHasVideo(videoCodec = "hevc", audioOnly = false))
        // Radio / music: audio-only hint or mpv's confirmation collapses the panel.
        assertFalse(mpvPanelHasVideo(videoCodec = null, audioOnly = true))
        assertFalse(mpvPanelHasVideo(videoCodec = " ", audioOnly = true))
        // A reported video codec wins over a stale hint.
        assertTrue(mpvPanelHasVideo(videoCodec = "h264", audioOnly = true))
    }

    @Test fun transitionValueIsMarkedUntilConfirmed() {
        assertEquals("MPV GPU · wechselt…", rendererTransitionValue("MPV GPU", confirmed = false, "wechselt…"))
        assertEquals("MPV GPU", rendererTransitionValue("MPV GPU", confirmed = true, "wechselt…"))
    }

    @Test fun mediaInfoRefreshesOnGenerationTimerAndSettlingValues() {
        assertTrue(mpvRefreshMediaInfo(generationChanged = true, valuesChanged = false, settling = false, sinceLastRefreshMs = 0))
        assertTrue(mpvRefreshMediaInfo(generationChanged = false, valuesChanged = false, settling = false, sinceLastRefreshMs = 2_000))
        // The value requested at the load arrives one sample later: taken at once while settling.
        assertTrue(mpvRefreshMediaInfo(generationChanged = false, valuesChanged = true, settling = true, sinceLastRefreshMs = 120))
        // Once settled, bitrate/position churn does not re-read the media info every tick.
        assertFalse(mpvRefreshMediaInfo(generationChanged = false, valuesChanged = true, settling = false, sinceLastRefreshMs = 120))
        assertFalse(mpvRefreshMediaInfo(generationChanged = false, valuesChanged = false, settling = true, sinceLastRefreshMs = 1_999))
        assertTrue(MPV_MEDIA_INFO_SETTLE_MS in 2_000L..10_000L)
    }

    @Test fun mediaInfoGenerationChangeForcesResample() {
        assertTrue(mpvMediaInfoChanged(sampledGeneration = -1, currentGeneration = 0))
        assertTrue(mpvMediaInfoChanged(sampledGeneration = 3, currentGeneration = 4))
        assertFalse(mpvMediaInfoChanged(sampledGeneration = 4, currentGeneration = 4))
    }

    @Test fun mpvNerdStatsPollFastDuringWarmupOnly() {
        assertEquals(250L, mpvNerdPollIntervalMs(warmup = true))
        assertEquals(750L, mpvNerdPollIntervalMs(warmup = false))
        assertEquals(250L, mpvSnapshotTickMs(playing = true, nerdStats = true, nerdWarmup = true))
        assertEquals(750L, mpvSnapshotTickMs(playing = true, nerdStats = true, nerdWarmup = false))
        // Warmup only speeds up Stats for Nerds, not the plain playback tick.
        assertEquals(mpvSnapshotTickMs(playing = false, nerdStats = false), mpvSnapshotTickMs(playing = false, nerdStats = false, nerdWarmup = true))
        assertEquals(3_000L, MPV_NERD_WARMUP_MS)
    }

    @Test fun vlcStreamInfoWarmsUpThenSlowsDown() {
        assertTrue(vlcStreamInfoWarmup(sinceStartMs = 0, statsKnown = false))
        assertTrue(vlcStreamInfoWarmup(sinceStartMs = 1_000, statsKnown = true))
        // Stats still missing: keep looking quickly a bit longer, but not forever.
        assertTrue(vlcStreamInfoWarmup(sinceStartMs = VLC_STREAM_INFO_WARMUP_MS + 500, statsKnown = false))
        assertFalse(vlcStreamInfoWarmup(sinceStartMs = VLC_STREAM_INFO_WARMUP_MS + 500, statsKnown = true))
        assertFalse(vlcStreamInfoWarmup(sinceStartMs = 2 * VLC_STREAM_INFO_WARMUP_MS, statsKnown = false))
        assertEquals(250L, vlcStreamInfoIntervalMs(warmup = true))
        assertEquals(1_000L, vlcStreamInfoIntervalMs(warmup = false))
    }

    @Test fun nerdStatsMarkBackendAsSwitchingUntilStreamReported() {
        val labels = NerdStatsOverlayLabels(switching = "wechselt…")
        fun backend(model: NerdStatsOverlayModel) = model.system.first { it.label == labels.backend }.value
        assertEquals("MPV · wechselt…", backend(buildMpvNerdOverlayModel(MpvPlaybackSnapshot(), labels)))
        assertEquals("MPV", backend(buildMpvNerdOverlayModel(MpvPlaybackSnapshot(audioCodec = "aac"), labels)))
        assertEquals("VLC · wechselt…", backend(buildVlcNerdOverlayModel(VlcPlaybackSnapshot(), labels)))
        assertEquals("Media3 · wechselt…", backend(buildMedia3NerdOverlayModel(Media3NerdStats(), labels)))
        assertEquals("Media3", backend(buildMedia3NerdOverlayModel(Media3NerdStats(), labels, videoSummary = "1920×1080")))
    }
}
