package com.ambient.tv.ui.display

import org.junit.Assert.*
import org.junit.Test

class SdrToHdrPolicyTest {
    private val display = UiHdrEligibility(34, true, true, true, true, true, 1f)

    @Test fun manualTargetRespectsDisplayAndRendererLimits() {
        assertEquals(400, sdrToHdrRequest(display, true, 800f, 1, 2, requestedPeakNits = 400)?.peakNits)
        assertEquals(350, sdrToHdrRequest(display, true, 350f, 1, 2, requestedPeakNits = 600)?.peakNits)
        assertEquals(600, sdrToHdrRequest(display, true, 800f, 1, 2, requestedPeakNits = 2000)?.peakNits)
        assertEquals(300, sdrToHdrRequest(display, true, 800f, 1, 2, requestedPeakNits = -1)?.peakNits)
        assertNull(sdrToHdrRequest(display.copy(displayHdr = false), true, 800f, 1, 2, true, 600))
        val unknown = display.copy(headroom = null)
        assertNull(sdrToHdrRequest(unknown, true, null, 1, 2, requestedPeakNits = 600))
        assertEquals(500, sdrToHdrRequest(unknown, true, null, 1, 2, true, 500)?.peakNits)
        assertEquals(300, sdrToHdrRequest(unknown, true, null, 1, 2, true, 0)?.peakNits)
    }

    @Test fun playbackControlRequiresKnownUsableVideoPath() {
        val ready = SdrToHdrSwitchFacts(35, true, true, "auto", false, 1f, 400f,
            player = SdrToHdrPlayerKind.MEDIA3, frameWidth = 1920, frameHeight = 1080, frameRate = 24f)
        assertTrue(sdrToHdrPlaybackControlVisible(ready))
        for (unusable in listOf(ready.copy(player = SdrToHdrPlayerKind.NONE),
            ready.copy(frameWidth = 0), ready.copy(frameRate = Float.NaN),
            ready.copy(frameRate = 0f), ready.copy(frameWidth = 3840, frameHeight = 2160),
            ready.copy(pathBlocked = true), ready.copy(mode = "sdr"), ready.copy(displayHdr = false))) {
            assertFalse(sdrToHdrPlaybackControlVisible(unusable))
        }
        assertTrue(sdrToHdrPlaybackControlVisible(ready.copy(player = SdrToHdrPlayerKind.MPV_VULKAN)))
        assertFalse(sdrToHdrPlaybackControlVisible(ready.copy(player = SdrToHdrPlayerKind.MPV_VULKAN, pathBlocked = true)))
    }

    @Test fun defaultsAndMasterSwitchNeverRequestVideoConversion() {
        assertNull(sdrToHdrRequest(display, false, 800f, 1, 2))
        assertNull(sdrToHdrRequest(display.copy(automatic = false), true, 800f, 1, 2))
    }

    @Test fun everyUnknownDisplayFactKeepsTheOldOutput() {
        listOf(display.copy(sdk = 33), display.copy(windowReady = false),
            display.copy(displayHdr = false), display.copy(wideColor = false),
            display.copy(sourceSupported = false), display.copy(headroom = null),
            display.copy(headroom = Float.NaN), display.copy(headroom = Float.POSITIVE_INFINITY),
            display.copy(headroom = .9f)).forEach {
            assertNull(sdrToHdrRequest(it, true, 800f, 1, 2))
        }
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 203f, 299f).forEach {
            assertNull(sdrToHdrRequest(display, true, it, 1, 2))
        }
    }

    @Test fun doesNotWaitForItsOwnHdrFramesAndCapsReportedPeak() {
        assertEquals(SdrToHdrRequest(600, 1, 2), sdrToHdrRequest(display, true, 1500f, 1, 2))
        assertEquals(SdrToHdrRequest(350, 1, 3), sdrToHdrRequest(display, true, 350f, 1, 3))
        assertNotEquals(sdrToHdrRequest(display, true, 600f, 1, 2),
            sdrToHdrRequest(display, true, 600f, 1, 3))
    }

    @Test fun unmeasuredHdrTvCanProbeOnlyWithVerifiedVideoSurfacePath() {
        val tv = display.copy(headroom = null)
        assertNull(sdrToHdrRequest(tv, true, 100f, 1, 2))
        assertEquals(SdrToHdrRequest(300, 1, 2), sdrToHdrRequest(tv, true, 100f, 1, 2, true))
        assertNull(sdrToHdrRequest(tv.copy(sourceSupported = false), true, 100f, 1, 2, true))
        assertNull(sdrToHdrRequest(tv, false, 100f, 1, 2, true))
        val facts = SdrToHdrSwitchFacts(34, true, true, "auto", false, null, 100f,
            player = SdrToHdrPlayerKind.MPV_VULKAN)
        assertTrue(sdrToHdrSwitchEnabled(facts))
        assertFalse(sdrToHdrSwitchEnabled(facts.copy(player = SdrToHdrPlayerKind.MEDIA3)))
        assertFalse(sdrToHdrSwitchEnabled(facts.copy(pathBlocked = true)))
    }

    @Test fun interfaceChoiceIsHiddenUnlessCappedHdrCanApply() {
        assertFalse(hdrInterfaceChoiceVisible(34, true, true))
        assertFalse(hdrInterfaceChoiceVisible(35, false, true))
        assertFalse(hdrInterfaceChoiceVisible(35, true, false))
        assertTrue(hdrInterfaceChoiceVisible(35, true, true))
    }

    @Test fun sdrToHdrSwitchStaysOnTheVideoPathAndHidesIneligiblePlayers() {
        val ready = SdrToHdrSwitchFacts(35, true, true, "auto", false, 1f, 400f)
        assertTrue(sdrToHdrSwitchVisible(ready.copy(sdk = 34)))
        assertFalse(sdrToHdrSwitchVisible(ready.copy(sdk = 33)))
        assertFalse(sdrToHdrSwitchVisible(ready.copy(displayHdr = false)))
        assertFalse(hdrInterfaceChoiceVisible(34, true, true))
        assertTrue(sdrToHdrSwitchEnabled(ready))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(hdr10Supported = false)))
        assertTrue(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.MPV_VULKAN)))
        assertTrue(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.VLC_GL, frameWidth = 1920, frameHeight = 1080, frameRate = 24f)))
        assertTrue(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.MEDIA3, frameWidth = 1280, frameHeight = 720, frameRate = 60f)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(mode = "force")))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(mode = "sdr")))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(reducedEffects = true)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(ratio = null)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(ratio = .9f)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(ratio = Float.NaN)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(peakNits = null)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(peakNits = 200f)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.MPV_UNSUPPORTED)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.VLC_VULKAN)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.VLC_GL, frameWidth = 3840, frameHeight = 2160, frameRate = 24f)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.MEDIA3, frameWidth = 1920, frameHeight = 1080, frameRate = 61f)))
        assertFalse(sdrToHdrSwitchEnabled(ready.copy(pathBlocked = true, player = SdrToHdrPlayerKind.MPV_VULKAN)))
        assertTrue(sdrToHdrSwitchEnabled(ready.copy(player = SdrToHdrPlayerKind.MEDIA3)))
    }

    @Test fun unsupportedConversionIsHiddenRegardlessOfStoredPreference() {
        val ready = SdrToHdrSwitchFacts(35, true, true, "auto", false, 1f, 400f)
        listOf(
            ready.copy(sdk = 33),
            ready.copy(displayHdr = false),
            ready.copy(wideColor = false),
            ready.copy(hdr10Supported = false),
            ready.copy(reducedEffects = true),
            ready.copy(ratio = null),
            ready.copy(ratio = Float.NaN),
            ready.copy(ratio = Float.POSITIVE_INFINITY),
            ready.copy(ratio = .9f),
            ready.copy(peakNits = null),
            ready.copy(peakNits = Float.NaN),
            ready.copy(peakNits = Float.POSITIVE_INFINITY),
            ready.copy(peakNits = 299f),
            ready.copy(player = SdrToHdrPlayerKind.MPV_UNSUPPORTED),
            ready.copy(player = SdrToHdrPlayerKind.VLC_VULKAN),
            ready.copy(player = SdrToHdrPlayerKind.MEDIA3, pathBlocked = true),
            ready.copy(player = SdrToHdrPlayerKind.VLC_GL, frameWidth = 3840, frameHeight = 2160, frameRate = 24f),
            ready.copy(player = SdrToHdrPlayerKind.MEDIA3, frameWidth = 1920, frameHeight = 1080, frameRate = 61f),
        ).forEach { facts ->
            assertFalse("Unsupported control must be hidden: $facts", sdrToHdrSwitchVisible(facts))
            assertFalse(sdrToHdrSwitchEnabled(facts))
        }
    }

    @Test fun supportedConversionRemainsVisibleWhenTheUiModeDisablesIt() {
        val ready = SdrToHdrSwitchFacts(35, true, true, "auto", false, 1f, 400f)
        listOf("sdr", "force").forEach { mode ->
            assertTrue(sdrToHdrSwitchVisible(ready.copy(mode = mode)))
            assertFalse(sdrToHdrSwitchEnabled(ready.copy(mode = mode)))
        }
    }
}
