package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

class VlcSeekStateTest {
    @Test fun zeroIsRetainedWhileOpeningAndAfterNativeRejection() {
        val state = VlcSeekState()
        state.request(0)
        assertEquals(0L, state.targetMs)
        assertFalse(state.shouldSubmit(false, 50_000))
        assertFalse(state.expired(50_000)) // Opening is not seek-ready time.
        state.beginReadyWait(50_000)
        assertTrue(state.shouldSubmit(true, 50_000))
        state.submitted(false, 50_000)
        assertFalse(state.observe(0, 50_500))
        assertEquals(0L, state.targetMs)
        assertTrue(state.shouldSubmit(true, 50_500))
    }

    @Test fun acceptanceAndOldNonzeroClockCannotDiscardResume() {
        val state = VlcSeekState()
        state.request(95_000)
        state.beginReadyWait(0)
        state.submitted(true, 0)
        assertFalse(state.observe(20_000, 200))
        assertFalse(state.observe(21_000, 800))
        assertEquals(95_000L, state.targetMs)
        assertFalse(state.shouldSubmit(true, 2_999))
        assertTrue(state.shouldSubmit(true, 3_000))
    }

    @Test fun clockMustStayAlignedAndPausedSeeksCanConfirm() {
        val state = VlcSeekState()
        state.request(45_000)
        state.submitted(true, 0)
        assertFalse(state.observe(45_000, 100))
        assertFalse(state.observe(12_000, 200)) // A transient optimistic clock is insufficient.
        assertFalse(state.observe(45_000, 300))
        assertFalse(state.observe(45_000, 599))
        assertTrue(state.observe(45_000, 600))
        assertNull(state.targetMs)
    }

    @Test fun replacementSeekDropsPreviousAcknowledgementAndZeroCanConfirm() {
        val state = VlcSeekState()
        state.request(45_000)
        state.submitted(true, 0)
        state.observe(45_000, 100)
        state.request(0)
        assertFalse(state.observe(0, 500))
        state.submitted(true, 600)
        assertFalse(state.observe(0, 700))
        assertTrue(state.observe(0, 1_000))
        assertNull(state.targetMs)
    }

    @Test fun acceptedSeekRetriesAreBoundedAndTimeoutKeepsFallbackPosition() {
        val state = VlcSeekState()
        state.request(90_000)
        state.beginReadyWait(1_000)
        state.submitted(true, 1_000)
        state.submitted(true, 4_000)
        assertFalse(state.shouldSubmit(true, 10_000))
        assertFalse(state.expired(30_999))
        assertTrue(state.expired(31_000))
        assertEquals(90_000L, state.targetMs)
        state.request(90_000) // Reopen starts a fresh bounded attempt.
        state.beginReadyWait(40_000)
        assertTrue(state.shouldSubmit(true, 40_000))
        assertFalse(state.expired(40_000))
    }
}
