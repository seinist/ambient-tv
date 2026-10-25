package com.ambient.tv.ui.player.backend

import com.ambient.tv.R
import com.ambient.tv.ui.display.DisplayHdrRatioEvidence
import com.ambient.tv.ui.display.displayHdrModeFromRatio
import com.ambient.tv.ui.display.displayHdrRatioMeansHdr
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.mpvHdrGpuReasonRes
import com.ambient.tv.ui.player.mpvHdrPassthroughStatusRes
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MpvHdrDisplaySwitchTest {
    private val pqSurface = (6 shl 16) or (7 shl 22) or (1 shl 27)
    private fun facts(ratio: Float?, reliable: Boolean = true, playing: Boolean = true, gamma: String = "pq") =
        MpvHdrPassthroughFacts(true, playing, "pq", gamma, "bt.2020", "rgb10a2", pqSurface, true,
            displayHdrSdrRatio = ratio, displayRatioReliable = reliable)

    @After fun clear() {
        MpvHdrGpuSession.clear()
        DisplayHdrRatioEvidence.clear()
    }

    @Test fun ratioReadsAsHdrSdrOrUnknown() {
        assertNull(displayHdrRatioMeansHdr(null))
        assertNull(displayHdrRatioMeansHdr(Float.NaN))
        assertNull(displayHdrRatioMeansHdr(0f))
        assertEquals(false, displayHdrRatioMeansHdr(1f))
        assertEquals(false, displayHdrRatioMeansHdr(1.04f))
        assertEquals(true, displayHdrRatioMeansHdr(2.5f))
        // 1.0 only means SDR on a display that proved the signal before.
        assertNull(displayHdrModeFromRatio(1f, reliable = false))
        assertEquals(false, displayHdrModeFromRatio(1f, reliable = true))
        assertEquals(true, displayHdrModeFromRatio(4f, reliable = false))
    }

    @Test fun evidenceIsPerDisplayAndOnlyFromAnHdrReading() {
        DisplayHdrRatioEvidence.note(0, 1f, hdrOutput = true, nowMs = 0L)
        DisplayHdrRatioEvidence.note(0, null, hdrOutput = true, nowMs = 1_000L)
        assertFalse(DisplayHdrRatioEvidence.reliable(0))
        repeat(DisplayHdrRatioEvidence.REQUIRED_HDR_READINGS) {
            DisplayHdrRatioEvidence.note(0, 3f, hdrOutput = true, nowMs = 2_000L + it * 1_000L)
        }
        assertTrue(DisplayHdrRatioEvidence.reliable(0))
        assertFalse(DisplayHdrRatioEvidence.reliable(1))
    }

    // R1
    @Test fun oneStrayReadingOrSdrOutputProvesNothing() {
        DisplayHdrRatioEvidence.note(0, 3f, hdrOutput = true, nowMs = 0L)
        assertFalse(DisplayHdrRatioEvidence.reliable(0))
        // Readings in quick succession are one observation.
        DisplayHdrRatioEvidence.note(0, 3f, hdrOutput = true, nowMs = 100L)
        DisplayHdrRatioEvidence.note(0, 3f, hdrOutput = true, nowMs = 200L)
        assertFalse(DisplayHdrRatioEvidence.reliable(0))
        // A ratio while the app outputs SDR (UI animation, other app) does not count.
        repeat(5) { DisplayHdrRatioEvidence.note(1, 3f, hdrOutput = false, nowMs = it * 1_000L) }
        assertFalse(DisplayHdrRatioEvidence.reliable(1))
        // Barely above 1 is not HDR evidence.
        repeat(5) { DisplayHdrRatioEvidence.note(2, 1.01f, hdrOutput = true, nowMs = it * 1_000L) }
        assertFalse(DisplayHdrRatioEvidence.reliable(2))
    }

    @Test fun anotherDisplayBehindTheSameIdStartsUnproven() {
        repeat(3) { DisplayHdrRatioEvidence.note(0, 3f, hdrOutput = true, displayKey = "TV A|2+3", nowMs = it * 1_000L) }
        assertTrue(DisplayHdrRatioEvidence.reliable(0, "TV A|2+3"))
        assertFalse(DisplayHdrRatioEvidence.reliable(0, "AVR|2"))
        DisplayHdrRatioEvidence.note(0, 1f, hdrOutput = true, displayKey = "AVR|2", nowMs = 5_000L)
        assertFalse(DisplayHdrRatioEvidence.reliable(0, "AVR|2"))
        // Back on the first TV the old proof is gone as well (the display changed in between).
        assertFalse(DisplayHdrRatioEvidence.reliable(0, "TV A|2+3"))
    }

    @Test fun sdrRatioOnAProvenDisplayFailsAfterSteadyPlayback() {
        val monitor = MpvHdrPassthroughMonitor(timeoutMs = 3_000L, displayTimeoutMs = 4_000L)
        assertNull(monitor.update(facts(1f), 0L))
        assertTrue(monitor.confirmed)
        assertEquals(false, monitor.displaySwitched)
        // Pauses do not count towards the window.
        assertNull(monitor.update(facts(1f, playing = false), 3_000L))
        assertNull(monitor.update(facts(1f), 3_500L))
        assertNull(monitor.update(facts(1f), 7_499L))
        assertEquals(MpvHdrGpuReason.DISPLAY_NOT_SWITCHED, monitor.update(facts(1f), 7_500L))
        assertEquals(MpvHdrGpuReason.DISPLAY_NOT_SWITCHED, monitor.failure)
    }

    @Test fun aLateSwitchIsAcceptedAndThenFinal() {
        val monitor = MpvHdrPassthroughMonitor(timeoutMs = 3_000L, displayTimeoutMs = 4_000L)
        assertNull(monitor.update(facts(1f), 0L))
        assertNull(monitor.update(facts(3.2f), 2_000L))
        assertEquals(true, monitor.displaySwitched)
        // Once switched, a later 1.0 (e.g. a dim scene report) does not fail the route.
        assertNull(monitor.update(facts(1f), 10_000L))
        assertNull(monitor.update(facts(1f), 20_000L))
        assertNull(monitor.failure)
    }

    @Test fun unknownOrUnprovenRatioNeverFails() {
        val unknown = MpvHdrPassthroughMonitor(displayTimeoutMs = 1_000L)
        assertNull(unknown.update(facts(null), 0L))
        assertNull(unknown.update(facts(null), 60_000L))
        assertNull(unknown.displaySwitched)
        val unproven = MpvHdrPassthroughMonitor(displayTimeoutMs = 1_000L)
        assertNull(unproven.update(facts(1f, reliable = false), 0L))
        assertNull(unproven.update(facts(1f, reliable = false), 60_000L))
        assertNull(unproven.displaySwitched)
        assertNull(unproven.failure)
    }

    @Test fun lostPqStillReportsNotConfirmed() {
        val monitor = MpvHdrPassthroughMonitor(timeoutMs = 1_000L, displayTimeoutMs = 10_000L)
        assertNull(monitor.update(facts(3f), 0L))
        assertNull(monitor.update(facts(3f, gamma = "bt.1886"), 1_000L))
        assertEquals(MpvHdrGpuReason.NOT_CONFIRMED, monitor.update(facts(3f, gamma = "bt.1886"), 2_000L))
    }

    @Test fun notSwitchedEscalatesToTheDisplayOnlyTwiceInARow() {
        MpvHdrGpuSession.recordFailure("a", null, "tv", MpvHdrGpuReason.DISPLAY_NOT_SWITCHED)
        assertEquals(MpvHdrGpuReason.DISPLAY_NOT_SWITCHED, MpvHdrGpuSession.failure("a", null, "tv"))
        assertNull(MpvHdrGpuSession.failure("b", null, "tv"))
        // A display that really switched in between resets the count.
        MpvHdrGpuSession.recordDisplaySwitched("tv")
        MpvHdrGpuSession.recordFailure("b", null, "tv", MpvHdrGpuReason.DISPLAY_NOT_SWITCHED)
        assertNull(MpvHdrGpuSession.failure("c", null, "tv"))
        MpvHdrGpuSession.recordFailure("c", null, "tv", MpvHdrGpuReason.DISPLAY_NOT_SWITCHED)
        assertEquals(MpvHdrGpuReason.DISPLAY_NOT_SWITCHED, MpvHdrGpuSession.failure("d", null, "tv"))
        assertNull(MpvHdrGpuSession.failure("d", null, "avr"))
    }

    @Test fun notSwitchedRoutesDirectWithoutTouchingTheChoice() {
        val vulkan12 = (1L shl 22) or (2L shl 12)
        val caps = VideoAccelerationCapabilities(
            checked = true,
            device = VulkanDeviceCapabilities(render = true, device = "gpu", version = vulkan12, hostQueryReset = true,
                surfaceColorSpaces = listOf(0, 1000104008)),
            mpvOutputBuilt = true,
        )
        val decision = mpvHdrGpuDecision(true, "auto", HdrStandard.HDR10, setOf(HdrStandard.HDR10), caps, false,
            MpvHdrGpuReason.DISPLAY_NOT_SWITCHED, false, true, true)
        assertEquals(MpvHdrGpuReason.DISPLAY_NOT_SWITCHED, decision.reason)
        assertEquals("direct", mpvHdrGpuOutputMode(decision, "auto", MpvHdrGpuReason.DISPLAY_NOT_SWITCHED))
    }

    @Test fun statusTextSaysWhatIsKnown() {
        assertEquals(R.string.player_output_hdr_gpu_checking, mpvHdrPassthroughStatusRes(false, null))
        assertEquals(R.string.player_output_hdr_gpu_active, mpvHdrPassthroughStatusRes(true, null))
        assertEquals(R.string.player_output_hdr_gpu_display_switched, mpvHdrPassthroughStatusRes(true, true))
        assertEquals(R.string.player_output_hdr_gpu_display_waiting, mpvHdrPassthroughStatusRes(true, false))
        assertEquals(R.string.player_output_hdr_gpu_display_not_switched, mpvHdrGpuReasonRes(MpvHdrGpuReason.DISPLAY_NOT_SWITCHED))
        assertEquals("unknown", mpvHdrDisplayStateLabel(null))
        assertEquals("sdr", mpvHdrDisplayStateLabel(false))
    }
}
