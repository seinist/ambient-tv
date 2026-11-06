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


    @Test fun gpuAndBuiltinMpvSharpeningAppearAsRealAppliedFilters() {
        assertEquals("sharpen GPU (CAS)", summarizeMpvVideoFilters("[]", listOf("cas")))
        assertEquals("sharpen GPU (CAS light), sharpen GPU (CAS medium)",
            summarizeMpvVideoFilters(null, listOf("cas_light", "cas_medium")))
        assertEquals("sharpen GPU (Adaptive Sharpen)",
            summarizeMpvVideoFilters(null, listOf("adaptive")))
        assertEquals("sharpen OpenGL (0.5)",
            summarizeMpvVideoFilters(null, emptyList(), "0.5"))
        assertEquals("off", summarizeMpvVideoFilters("[]", emptyList(), "0"))
        assertEquals("denoise, sharpen GPU (CAS)",
            summarizeMpvVideoFilters("@plex-denoise:lavfi=[hqdn3d]", listOf("cas")))
        assertEquals("deblock GPU (medium), sharpen GPU (CAS)",
            summarizeMpvVideoFilters(null, listOf("deblock_medium", "cas")))
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
