package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFpsOverlayTest {
    @Test
    fun formatsFpsAndRefreshTogether() {
        assertEquals("23.976 fps · 59.940 Hz · 3:2 pulldown", formatPlaybackFpsOverlay(23.976, 59.94))
    }

    @Test fun cadenceRequiresMatchingActiveRates() {
        assertEquals("Double frame rate", playbackCadenceLabel(25.0, 50.0))
        assertEquals("Double frame rate", playbackCadenceLabel(29.97, 59.94))
        assertEquals("3:2 pulldown", playbackCadenceLabel(24.0, 60.0))
        assertNull(playbackCadenceLabel(24.0, 24.0))
        assertNull(playbackCadenceLabel(25.0, 60.0))
        assertNull(playbackCadenceLabel(60.0, 60.0)) // Interpolated output is native.
        assertNull(playbackCadenceLabel(null, 60.0))
        assertNull(playbackCadenceLabel(Double.NaN, 60.0))
    }

    @Test
    fun formatsEitherPartAlone() {
        assertEquals("24.000 fps", formatPlaybackFpsOverlay(24.0, null))
        assertEquals("60.000 Hz", formatPlaybackFpsOverlay(null, 60.0))
    }

    @Test
    fun returnsNullWhenBothMissing() {
        assertNull(formatPlaybackFpsOverlay(null, null))
        assertNull(formatPlaybackFpsOverlay(0.0, Double.NaN))
    }

    @Test
    fun prefersEstimatedFpsOverContainer() {
        assertEquals(59.94, playbackOverlayFps(59.94, 23.976))
        assertEquals(23.976, playbackOverlayFps(0.0, 23.976))
        assertNull(playbackOverlayFps(null, null))
    }
    @Test fun displaysDecodedVideoAndActiveOutputOnSeparateLines() {
        assertEquals("Video 1920×1080 · 23.976 fps\nDisplay 3840×2160 · 59.940 Hz · 3:2 pulldown",
            formatPlaybackFpsOverlay(23.976, 59.94, "1920×1080", "3840×2160"))
        assertEquals("Video –\nDisplay 1920×1080 · 50.000 Hz",
            formatPlaybackFpsOverlay(null, 50.0, null, "1920×1080"))
        assertNull(formatPlaybackResolution(-1, 1080))
        assertNull(formatPlaybackResolution(1920, 0))
    }

}
