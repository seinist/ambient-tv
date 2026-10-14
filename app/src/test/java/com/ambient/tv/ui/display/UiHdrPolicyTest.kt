package com.ambient.tv.ui.display

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.ambient.tv.ui.player.HdrStandard
import org.junit.Assert.*
import org.junit.Test

class UiHdrPolicyTest {
    private val supported = UiHdrEligibility(35, true, true, true, true, true, 2f)

    private class Output : UiHdrOutput {
        var hdrCalls = 0
        var sdrCalls = 0
        var throwHdr = false
        var throwSdr = false
        var active = false
        override fun hdr(headroom: Float) {
            hdrCalls++
            active = true // The platform can throw after partially applying a request.
            if (throwHdr) error("HDR setter failed")
        }
        override fun sdr() {
            sdrCalls++
            active = false
            if (throwSdr) error("SDR setter failed")
        }
    }

    @Test fun everyMissingCapabilityKeepsTheOriginalOutputUntouched() {
        val unsupported = listOf(
            supported.copy(sdk = 33), supported.copy(sdk = 34), supported.copy(automatic = false),
            supported.copy(windowReady = false), supported.copy(displayHdr = false),
            supported.copy(wideColor = false), supported.copy(sourceSupported = false),
            supported.copy(headroom = null), supported.copy(headroom = Float.NaN),
            supported.copy(headroom = Float.POSITIVE_INFINITY), supported.copy(headroom = 1f),
        )
        unsupported.forEach { facts ->
            val output = Output()
            val controller = UiHdrController(output)
            controller.update(facts, "source", 0)
            controller.update(facts, "source", 10_000)
            assertEquals(1f, controller.headroom, 0f)
            assertEquals(0, output.hdrCalls)
            assertEquals(0, output.sdrCalls)
        }
    }

    @Test fun enablesAfterStabilisingCapsHighlightsAndAvoidsRepeatedWindowWrites() {
        val output = Output()
        val controller = UiHdrController(output)
        controller.update(supported, "source", 0)
        controller.update(supported, "source", 999)
        assertEquals(0, output.hdrCalls)
        controller.update(supported, "source", 1_000)
        assertEquals(UI_HDR_HEADROOM_CAP, controller.headroom, 0f)
        controller.update(supported, "source", 2_000)
        assertEquals(1, output.hdrCalls)
        controller.update(supported.copy(headroom = 1.1f), "source", 2_500)
        assertEquals(UI_HDR_HEADROOM_CAP, controller.headroom, 0f)
        assertEquals(1, output.hdrCalls)
    }

    @Test fun rendererAndDisplaySwitchRestoreSdrBeforeAnotherHdrRequest() {
        val output = Output()
        val controller = UiHdrController(output)
        controller.update(supported, "media3-display1", 0)
        controller.update(supported, "media3-display1", 1_000)
        controller.update(supported, "mpv-display2", 1_500)
        assertFalse(output.active)
        assertEquals(1f, controller.headroom, 0f)
        controller.update(supported, "mpv-display2", 2_500)
        assertTrue(output.active)
        controller.update(supported.copy(sourceSupported = false), null, 2_501)
        assertFalse(output.active)
        assertEquals(2, output.sdrCalls)
    }

    @Test fun partialFailureRollsBackAndLatchesUntilNewActivity() {
        val output = Output().apply { throwHdr = true }
        val controller = UiHdrController(output)
        controller.update(supported, "source", 0)
        controller.update(supported, "source", 1_000)
        assertTrue(controller.failed)
        assertFalse(output.active)
        output.throwHdr = false
        controller.reset()
        controller.update(supported, "new source", 5_000)
        controller.update(supported, "new source", 6_000)
        assertEquals(1, output.hdrCalls)
        assertEquals(1f, controller.headroom, 0f)
    }

    @Test fun restorationFailureDoesNotCauseAnHdrRetryLoop() {
        val output = Output()
        val controller = UiHdrController(output)
        controller.update(supported, "source", 0)
        controller.update(supported, "source", 1_000)
        output.throwSdr = true
        controller.reset()
        controller.update(supported, "source", 5_000)
        assertTrue(controller.failed)
        assertEquals(1, output.hdrCalls)
    }

    @Test fun suspendAndStopAlwaysRestoreSdr() {
        val output = Output()
        val controller = UiHdrController(output)
        controller.update(supported, "source", 0)
        controller.update(supported, "source", 1_000)
        controller.reset()
        assertFalse(output.active)
        assertEquals(1f, controller.headroom, 0f)
        controller.update(supported, "source", 2_000)
        assertFalse(output.active)
    }

    @Test fun hdrUsesExtendedValuesWhileSdrIsBitExact() {
        val original = Color(0xFFE5A00D)
        assertEquals(original, uiHdrHighlight(original, 1f))
        assertEquals(original, uiHdrHighlight(original, Float.NaN))
        val hdr = uiHdrHighlight(original, 8f)
        assertEquals(ColorSpaces.LinearExtendedSrgb, hdr.colorSpace)
        val peak = maxOf(hdr.red, hdr.green, hdr.blue)
        assertTrue("Near-white colors must clear the old 1.25 cap", peak > 1.25f)
        assertTrue(peak <= UI_HDR_HEADROOM_CAP + 0.001f)
        val white = uiHdrHighlight(Color.White, 8f)
        assertEquals(UI_HDR_HEADROOM_CAP, maxOf(white.red, white.green, white.blue), .02f)
        assertEquals(Color.Black, uiHdrHighlight(Color.Black, UI_HDR_HEADROOM_CAP))
        assertNull(uiHdrBitmapColorFilter(1f))
        assertNotNull(uiHdrBitmapColorFilter(UI_HDR_HEADROOM_CAP))
        val dark = uiHdrHighlight(Color(0xFF101010), 1.25f)
        assertTrue("Dim animation frames must remain dim", dark.red < .02f)
    }

    @Test fun unknownPreferencesNeverEnableHdr() {
        assertEquals("auto", normalizedUiHdrMode("auto"))
        assertEquals("force", normalizedUiHdrMode("force"))
        listOf(null, "", "dolby-vision", "always", "hdr", "sdr").forEach {
            assertEquals("sdr", normalizedUiHdrMode(it))
        }
    }

    @Test fun forceDoesNotRequireAPlayingHdrSourceButStillRequiresACapableDisplay() {
        val forced = UiHdrEligibility(
            sdk = 35,
            automatic = false,
            windowReady = true,
            displayHdr = true,
            wideColor = true,
            sourceSupported = false,
            headroom = null,
            forced = true,
        )
        assertTrue(forced.allowed)
        assertEquals(UI_HDR_HEADROOM_CAP, forced.requestedHeadroom, 0f)
        assertTrue(forced.copy(headroom = Float.NaN).allowed)
        assertEquals(UI_HDR_HEADROOM_CAP, forced.copy(headroom = 4f).requestedHeadroom, 0f)
        assertEquals(UI_HDR_HEADROOM_CAP, forced.copy(headroom = 1.1f).requestedHeadroom, 0f)
        listOf(
            forced.copy(sdk = 33),
            forced.copy(sdk = 34),
            forced.copy(windowReady = false),
            forced.copy(displayHdr = false),
            forced.copy(wideColor = false),
        ).forEach { assertFalse(it.allowed) }

        val output = Output()
        val controller = UiHdrController(output)
        controller.update(forced, "idle-ui", 0)
        controller.update(forced, "idle-ui", 1_000)
        assertEquals(UI_HDR_HEADROOM_CAP, controller.headroom, 0f)
        assertEquals(1, output.hdrCalls)

        val incapable = Output()
        val denied = UiHdrController(incapable)
        denied.update(forced.copy(displayHdr = false), "idle-ui", 0)
        denied.update(forced.copy(displayHdr = false, wideColor = false, sdk = 33), "idle-ui", 10_000)
        assertEquals(1f, denied.headroom, 0f)
        assertEquals(0, incapable.hdrCalls)
        assertEquals(0, incapable.sdrCalls)
    }

    @Test fun handheldUiRequiresMeasuredHeadroomBecauseHwuiDowngradesHdrWithoutIt() {
        val missing = supported.copy(headroom = null)
        assertFalse(missing.allowed)
        assertFalse(missing.copy(sourceSupported = false).allowed)
        assertTrue(missing.copy(headroom = 1.1f).allowed)
    }

    @Test fun handheldRefinesOnlyKnownRendererGaps() {
        assertEquals(HdrStandard.HDR10_PLUS, handheldHdrSource("media3", HdrStandard.HDR10, HdrStandard.HDR10_PLUS))
        assertEquals(HdrStandard.DOLBY_VISION, handheldHdrSource("media3", HdrStandard.HDR10, HdrStandard.DOLBY_VISION))
        assertEquals(HdrStandard.HLG, handheldHdrSource("media3", HdrStandard.HLG, HdrStandard.HDR10_PLUS))
        assertEquals(HdrStandard.HDR10, handheldHdrSource("vlc", null, HdrStandard.HDR10))
        assertNull(handheldHdrSource("mpv", null, HdrStandard.HDR10))
    }
}
