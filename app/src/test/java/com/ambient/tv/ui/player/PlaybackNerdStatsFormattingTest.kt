package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNerdStatsFormattingTest {
    @Test
    fun sameSizeVideoDoesNotReportTheConfiguredScalerAsActive() {
        assertEquals("off", formatMpvUpscalerForStats("lanczos", "lanczos", null, false))
        assertEquals("off", formatMpvUpscalerForStats("nnedi3_nns32", "lanczos", "nnedi3_nns32", false))
    }

    @Test
    fun nnediPresetKeepsShaderVisibleInsteadOfCollapsingToLanczos() {
        assertEquals(
            "NNEDI3 Quality (nns32) (loaded; conditional) → Lanczos finish",
            formatMpvUpscalerForStats("nnedi3_nns32", "lanczos", "nnedi3_nns32"),
        )
    }

    @Test
    fun idleNnediConfiguredScalerStaysVisibleInsteadOfCollapsingToLanczos() {
        assertEquals(
            "NNEDI3 Quality (nns32) (configured; idle) → Lanczos finish",
            formatMpvUpscalerForStats("nnedi3_nns32", "lanczos", null),
        )
    }

    @Test
    fun builtInScalerUsesRuntimeScaler() {
        assertEquals(
            "EWA LanczosSharp",
            formatMpvUpscalerForStats("lanczos", "ewa_lanczossharp", null),
        )
    }

    @Test
    fun nisPresetKeepsIntegratedScalerVisible() {
        assertEquals(
            "NVIDIA NIS · NVScaler (loaded; conditional) → Lanczos finish",
            formatMpvUpscalerForStats("nis", "lanczos", "nis"),
        )
    }

    @Test
    fun completeNnediQualityLadderHasStableStatsNames() {
        assertEquals("NNEDI3 Fast (nns16)", formatMpvScalerName("nnedi3_nns16"))
        assertEquals("NNEDI3 Quality (nns32)", formatMpvScalerName("nnedi3_nns32"))
        assertEquals("NNEDI3 High (nns64)", formatMpvScalerName("nnedi3_nns64"))
        assertEquals("NNEDI3 Very High (nns128)", formatMpvScalerName("nnedi3_nns128"))
        assertEquals("NNEDI3 Extreme (nns256)", formatMpvScalerName("nnedi3_nns256"))
        assertEquals("NNEDI3 Ultra (nns256, win8x6)", formatMpvScalerName("nnedi3_nns256_win8x6"))
    }


    @org.junit.Test fun fpsRowShowsSourceToOutputOnlyWhenTheRateChanges() {
        assertEquals("23.976 → 59.940 fps", formatNerdFpsChange(23.976, 59.94))
        assertEquals("23.976 fps", formatNerdFpsChange(23.976, 23.98))
        assertEquals("25.000 fps", formatNerdFpsChange(null, 25.0))
        assertEquals(null, formatNerdFpsChange(null, null))
    }

    @org.junit.Test fun svpStatusReplacesTheBareSvpToken() {
        assertEquals("SVP 24 → 60 fps", withSvpStatus("svp", "24 → 60 fps"))
        assertEquals("on · oversample · SVP 24 → 60 fps", withSvpStatus("on · oversample · svp", "24 → 60 fps"))
        assertEquals("SVP starting", withSvpStatus("off", "starting"))
        assertEquals("off", withSvpStatus("off", null))
    }
}
