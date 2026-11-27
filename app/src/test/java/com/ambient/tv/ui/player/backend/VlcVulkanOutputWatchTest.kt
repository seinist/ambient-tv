package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class VlcVulkanOutputWatchTest {
    @Test fun oldFramesDoNotProveNewSurfaceAndSuccessStopsSampling() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 100))
        assertFalse(watch.shouldSample(999))
        assertNull(watch.observe(1000, 101, 100))
        assertEquals(VlcVulkanOutputVerdict.CONFIRMED, watch.observe(2000, 102, 101))
        assertFalse(watch.shouldSample(100_000))
        watch.reset()
        assertTrue(watch.shouldSample(100_000))
    }

    @Test fun onlyDecodeProgressWithoutPresentationTriggersFallback() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 90))
        assertNull(watch.observe(8000, 100, 90))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(9000, 110, 90))
        assertFalse(watch.shouldSample(10000))
    }

    @Test fun experimentalPathContinuesMonitoringAfterFirstPresentation() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 90, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.CONFIRMED, watch.observe(1000, 101, 91, continuous = true))
        assertTrue(watch.shouldSample(2000))
        assertNull(watch.observe(2000, 102, 92, continuous = true))
        assertNull(watch.observe(9000, 110, 92, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(10000, 111, 92, continuous = true))
        assertFalse(watch.shouldSample(11000))
    }

    @Test fun experimentalDecoderStarvationWithoutCounterProgressIsBounded() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 90, continuous = true))
        assertNull(watch.observe(8000, 100, 90, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(15000, 100, 90, continuous = true))
        watch.reset()
        assertNull(watch.observe(0, null, null, continuous = true))
        assertNull(watch.observe(19000, null, null, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(20000, null, null, continuous = true))
    }

    @Test fun intermittentMissingOrResetCountersDoNotRenewExperimentalDeadline() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 90, continuous = true))
        assertNull(watch.observe(4000, null, null, continuous = true))
        assertNull(watch.observe(8000, 0, 0, continuous = true))
        assertNull(watch.observe(12000, null, null, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(15000, 0, 0, continuous = true))
    }

    @Test fun experimentalProgressAcrossMissingSamplesAndSuspensionRenewsDeadline() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 90, continuous = true))
        assertNull(watch.observe(10000, null, null, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.CONFIRMED, watch.observe(14000, 101, 91, continuous = true))
        assertNull(watch.observe(28000, 101, 91, continuous = true))
        watch.suspendObservation()
        assertNull(watch.observe(90000, 101, 91, continuous = true))
        assertNull(watch.observe(104000, null, null, continuous = true))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(110000, null, null, continuous = true))
    }

    @Test fun experimentalOpeningDeadlineTracksProgressAndPlaybackIntent() {
        val watch = VlcAhbOpeningWatch()
        assertFalse(watch.observe(0, 0, true))
        assertFalse(watch.observe(29000, 1, true))
        assertFalse(watch.observe(58000, 1, true))
        assertTrue(watch.observe(59000, 1, true))
        assertFalse(watch.observe(60000, 1, false))
        assertFalse(watch.observe(120000, 1, true))
        assertFalse(watch.observe(130000, 2, true))
        assertTrue(watch.observe(160000, 2, true))
    }

    @Test fun bufferingAndMissingOrResetCountersRestartObservation() {
        val watch = VlcVulkanOutputWatch()
        assertNull(watch.observe(0, 100, 90))
        watch.suspendObservation()
        assertNull(watch.observe(20_000, 120, 90))
        assertNull(watch.observe(21_000, null, null))
        assertNull(watch.observe(30_000, 130, 90))
        assertNull(watch.observe(31_000, 0, 0))
        assertNull(watch.observe(38_000, 10, 0))
        assertEquals(VlcVulkanOutputVerdict.STALLED, watch.observe(39_000, 11, 0))
    }
}
