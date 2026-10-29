package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTargetFrameRateTest {
    private fun mpv(
        source: Double? = 25.0,
        filtered: Double? = null,
        svpActive: Boolean = false,
        svpTarget: Double? = null,
        deinterlace: Boolean = false,
        automatic: Boolean = false,
        filter: String = "bwdif2x",
        singleRate: Boolean = false,
    ) = mpvTargetVideoFps(source, filtered, svpActive, svpTarget, deinterlace, automatic, filter, singleRate)

    @Test fun activeSvpUsesItsTargetEvenWhenTheSourceRateIsUnknown() {
        assertEquals(48.0, mpv(source = null, svpActive = true, svpTarget = 48.0)!!, 0.001)
        assertEquals(48.0, vlcTargetVideoFps(null, true, 48.0, "auto", "bob")!!, 0.001)
    }

    @Test fun inactiveSvpCannotTurnTheDisplayTargetIntoAVideoTarget() {
        assertEquals(25.0, mpv(svpTarget = 60.0)!!, 0.001)
        assertEquals(25.0, vlcTargetVideoFps(25.0, false, 60.0, "off", "bob")!!, 0.001)
    }

    @Test fun unknownAndInvalidRatesDoNotProduceAFalseTarget() {
        for (rate in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(mpv(source = rate))
            assertNull(vlcTargetVideoFps(rate, false, null, "off", "auto"))
            assertNull(mpv(svpActive = true, svpTarget = rate))
            assertNull(vlcTargetVideoFps(25.0, true, rate, "off", "auto"))
        }
    }

    @Test fun ordinaryPlaybackKeepsItsSourceTargetWhenTheFilteredRateSags() {
        assertEquals(23.976, mpv(source = 23.976, filtered = 20.0)!!, 0.001)
    }

    @Test fun forcedMpvDoublingKeepsANominalTargetAndHonorsTheFieldRateGuard() {
        assertEquals(50.0, mpv(filtered = 45.0, deinterlace = true)!!, 0.001)
        assertEquals(50.0, mpv(source = 50.0, deinterlace = true, singleRate = true)!!, 0.001)
        assertEquals(25.0, mpv(deinterlace = true, filter = "bwdif")!!, 0.001)
    }

    @Test fun automaticMpvDeinterlacingDistinguishesBypassFromDoubling() {
        assertEquals(25.0, mpv(filtered = 24.9, deinterlace = true, automatic = true)!!, 0.001)
        assertEquals(50.0, mpv(filtered = 50.1, deinterlace = true, automatic = true)!!, 0.001)
        assertNull(mpv(filtered = 40.0, deinterlace = true, automatic = true))
        assertNull(mpv(deinterlace = true, automatic = true))
    }

    @Test fun mpvIvtcUsesTheKnownCadenceInsteadOfTreatingRemovedFramesAsDrops() {
        assertEquals(23.976, mpv(source = 29.97, filtered = 23.98, deinterlace = true, filter = "ivtc")!!, 0.001)
        assertNull(mpv(source = 29.97, filtered = 20.0, deinterlace = true, filter = "ivtc"))
    }

    @Test fun vlcSingleRateMethodsStayKnownEvenWithAutomaticActivation() {
        for (filter in listOf("auto", "discard", "mean", "blend", "x", "yadif")) {
            assertEquals(filter, 25.0, vlcTargetVideoFps(25.0, false, null, "auto", filter)!!, 0.001)
        }
    }

    @Test fun vlcDoubleRateRequiresForcedActivation() {
        for (filter in listOf("bob", "linear", "yadif2x", "phosphor")) {
            assertEquals(filter, 50.0, vlcTargetVideoFps(25.0, false, null, "on", filter)!!, 0.001)
            assertEquals(filter, 25.0, vlcTargetVideoFps(25.0, false, null, "off", filter)!!, 0.001)
            assertNull(filter, vlcTargetVideoFps(25.0, false, null, "auto", filter))
        }
        assertNull(vlcTargetVideoFps(29.97, false, null, "on", "ivtc"))
    }
}
