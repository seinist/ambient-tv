package com.ambient.tv.ui.player

import com.ambient.tv.data.local.Media3ImageOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixes from the 26.10. audit: Media3 chain, filter budget, gamma fusion, audio restart and route reset. */
class AuditFixes2610Test {
    @After fun reset() = AudioCodecRejects.clear()

    @Test fun automaticEmptyChainUpdatesInsteadOfRestarting() {
        // H1: ABR/SD-HD/mini-player/budget changes keep playing; only a user change restarts.
        assertEquals(Media3ChainTransition.UPDATE, media3ChainTransition(prevActive = true, nextEmpty = true, gpuInitialized = true, userChange = false))
        assertEquals(Media3ChainTransition.RESTART, media3ChainTransition(prevActive = true, nextEmpty = true, gpuInitialized = true, userChange = true))
        // The very first chain still needs the GPU sink, whoever caused it.
        assertEquals(Media3ChainTransition.RESTART, media3ChainTransition(prevActive = false, nextEmpty = false, gpuInitialized = false, userChange = false))
    }

    @Test fun audioRestartWaitsForPreviousPlayer() {
        // M1: no prepare() while the previous player still releases its audio output.
        assertFalse(Media3AudioProcessing.media3AudioRestartNow(waitingForPreviousRelease = true))
        assertTrue(Media3AudioProcessing.media3AudioRestartNow(waitingForPreviousRelease = false))
    }

    @Test fun filterBudgetStopsAtFullHd60() {
        assertTrue(media3FilterBudgetAllows(1920, 1080, 60f))
        assertTrue(media3FilterBudgetAllows(3840, 2160, 0f).not())
        assertTrue(media3FilterBudgetAllows(2560, 1440, 30f))
        assertFalse(media3FilterBudgetAllows(2560, 1440, 60f))
        assertFalse(media3FilterBudgetAllows(3840, 2160, 24f))
        assertTrue("unknown size", media3FilterBudgetAllows(0, 0, 60f))
    }

    @Test fun planSkipsDebandAndSharpenOverBudgetButKeepsScaleAndColor() {
        val o = Media3ImageOptions(enabled = true, scaler = "fsr", deband = "light", sharpening = "light", gamma = 10)
        val full = media3EffectPlan(o, upscaling = true)
        assertTrue(full.any { it is Media3Stage.Deband })
        assertTrue(full.any { it is Media3Stage.Sharpen })
        val limited = media3EffectPlan(o, upscaling = true, debandAllowed = false, sharpenAllowed = false)
        assertEquals(listOf(Media3Stage.Scale("fsr"), Media3Stage.Gamma(10)), limited)
        val noScale = media3EffectPlan(o.copy(scaler = "bilinear"), upscaling = true, debandAllowed = true, sharpenAllowed = false)
        assertFalse(noScale.any { it is Media3Stage.Sharpen })
        assertTrue(noScale.any { it is Media3Stage.Deband })
    }

    @Test fun trailingGammaFusesIntoPreviousOwnPass() {
        // G10: one texture less.
        val fused = media3FusedStages(listOf(Media3Stage.Scale("fsr"), Media3Stage.Sharpen("rcas"), Media3Stage.Gamma(10)))
        assertEquals(listOf(Media3FusedStage(Media3Stage.Scale("fsr")), Media3FusedStage(Media3Stage.Sharpen("rcas"), 10)), fused)
        assertEquals(listOf(Media3FusedStage(Media3Stage.Deband("light"), -5)),
            media3FusedStages(listOf(Media3Stage.Deband("light"), Media3Stage.Gamma(-5))))
    }

    @Test fun gammaStaysSeparateWhereFusingWouldChangeTheResult() {
        val afterColor = listOf(Media3Stage.Sharpen("light"), Media3Stage.Contrast(10), Media3Stage.Gamma(10))
        assertEquals(afterColor.map { Media3FusedStage(it) }, media3FusedStages(afterColor))
        val lanczos = listOf(Media3Stage.Scale("lanczos"), Media3Stage.Gamma(10))
        assertEquals(lanczos.map { Media3FusedStage(it) }, media3FusedStages(lanczos))
        assertEquals(listOf(Media3FusedStage(Media3Stage.Gamma(10))), media3FusedStages(listOf(Media3Stage.Gamma(10))))
        assertEquals(emptyList<Media3FusedStage>(), media3FusedStages(emptyList()))
    }

    @Test fun routeBackWithMoreChannelsRetriesOutputAtMostTwice() {
        // M5
        assertTrue(audioFallbackResetOnRoute(fallbackActive = true, routeOffersMore = true, resetsSoFar = 0))
        assertTrue(audioFallbackResetOnRoute(fallbackActive = true, routeOffersMore = true, resetsSoFar = 1))
        assertFalse(audioFallbackResetOnRoute(fallbackActive = true, routeOffersMore = true, resetsSoFar = MAX_OUTPUT_FALLBACK_RESETS))
        assertFalse(audioFallbackResetOnRoute(fallbackActive = false, routeOffersMore = true, resetsSoFar = 0))
        assertFalse("stereo-only route: stay on the fallback", audioFallbackResetOnRoute(fallbackActive = true, routeOffersMore = false, resetsSoFar = 0))
    }

    @Test fun clearingOutputRejectsKeepsSourceFacts() {
        AudioCodecRejects.reject("eac3")
        AudioCodecRejects.markDtsCoreMissing()
        val before = AudioCodecRejects.version
        AudioCodecRejects.clearOutputRejects()
        assertTrue(AudioCodecRejects.version > before)
        assertTrue(AudioCodecRejects.dtsCoreMissing)
        val after = AudioCodecRejects.version
        AudioCodecRejects.clearOutputRejects()
        assertEquals("nothing to clear, no re-plan", after, AudioCodecRejects.version)
    }
}
