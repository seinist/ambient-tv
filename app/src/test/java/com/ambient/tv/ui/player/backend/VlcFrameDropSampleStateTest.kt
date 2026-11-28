package com.ambient.tv.ui.player.backend

import org.junit.Assert.*
import org.junit.Test

/** Tests the actual state consumed by the controller, without constructing native LibVLC. */
class VlcFrameDropSampleStateTest {
    @Test fun bufferingIsBlockedUntilCompleteWithoutResettingForEveryPercentage() {
        val state = VlcFrameDropSampleState()
        state.onEvent(VlcEventKind.Buffering, 0f)
        val bufferingGeneration = state.generation
        assertTrue(state.buffering)
        state.onEvent(VlcEventKind.Buffering, 25f)
        state.onEvent(VlcEventKind.Buffering, 75f)
        assertEquals(bufferingGeneration, state.generation)
        state.onEvent(VlcEventKind.Buffering, 100f)
        assertFalse(state.buffering)
        assertNotEquals(bufferingGeneration, state.generation)
        val readyGeneration = state.generation
        state.onEvent(VlcEventKind.Buffering, 100f)
        assertEquals(readyGeneration, state.generation)
    }

    @Test fun shortPauseAndReprepareCannotDisappearBetweenPolls() {
        val state = VlcFrameDropSampleState()
        val playingGeneration = state.generation
        state.onEvent(VlcEventKind.Paused)
        state.onEvent(VlcEventKind.Playing)
        assertNotEquals(playingGeneration, state.generation)

        state.onEvent(VlcEventKind.Buffering, 10f)
        val previousStream = state.generation
        state.prepare()
        assertFalse(state.buffering) // Old stream buffering must not block its replacement.
        assertNotEquals(previousStream, state.generation)
        val reopened = state.generation
        state.discontinuity() // The controller invokes this before submitting an explicit seek.
        assertNotEquals(reopened, state.generation)
    }

    @Test fun normalClockAndTrackUpdatesLeaveTheCounterWindowIntact() {
        val state = VlcFrameDropSampleState()
        val baseline = state.generation
        state.onEvent(VlcEventKind.TimeChanged)
        state.onEvent(VlcEventKind.PositionChanged)
        state.onEvent(VlcEventKind.ESAdded)
        assertEquals(baseline, state.generation)
        assertFalse(state.buffering)
    }
}
