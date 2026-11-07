package com.ambient.tv.ui.sound

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSoundEventGateTest {
    @Test fun everyDirectionSoundsWithoutAFocusCallback() {
        val gate = UiSoundEventGate()
        for (keyCode in listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT)) {
            val input = requireNotNull(gate.input(keyCode, 100))
            // Consumed navigation and presses at an edge may never change focus.
            assertEquals(UiSound.FOCUS, gate.complete(input))
            assertNull(gate.complete(input))
        }
    }

    @Test fun rapidIndependentPressesAllSurviveBeforeAnyFocusOrCompletion() {
        val gate = UiSoundEventGate()
        val inputs = (100L..103L).map { time ->
            requireNotNull(gate.input(KEYCODE_DPAD_LEFT, time))
        }

        assertEquals(List(4) { UiSound.FOCUS }, inputs.map(gate::complete))
        inputs.forEach { assertNull(gate.complete(it)) }
    }

    @Test fun differentDirectionsAtTheSameTimestampAreIndependent() {
        val gate = UiSoundEventGate()
        val inputs = listOf(KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT).map {
            requireNotNull(gate.input(it, 100))
        }

        assertEquals(List(4) { UiSound.FOCUS }, inputs.map(gate::complete))
    }

    @Test fun sameNativeEventSeenAtNestedRootsCannotCreateOrRearmFeedback() {
        val gate = UiSoundEventGate()
        val input = requireNotNull(gate.input(KEYCODE_DPAD_RIGHT, 100))
        assertNull(gate.input(KEYCODE_DPAD_RIGHT, 100))
        assertFalse(gate.accept(UiSound.FOCUS))

        assertEquals(UiSound.FOCUS, gate.complete(input))
        assertNull(gate.input(KEYCODE_DPAD_RIGHT, 100))
        assertFalse(gate.accept(UiSound.FOCUS))
        assertNull(gate.complete(input))

        val next = requireNotNull(gate.input(KEYCODE_DPAD_RIGHT, 101))
        assertEquals(UiSound.FOCUS, gate.complete(next))
    }

    @Test fun releaseLetsASecondPressAtTheSameTimestampSoundIndependently() {
        val gate = UiSoundEventGate()
        val first = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        assertNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        gate.releaseInput(KEYCODE_DPAD_LEFT)
        val second = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))

        assertEquals(listOf(UiSound.FOCUS, UiSound.FOCUS), listOf(first, second).map(gate::complete))
    }

    @Test fun releasingAnotherKeyDoesNotRearmTheSameDownEvent() {
        val gate = UiSoundEventGate()
        val input = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        gate.releaseInput(KEYCODE_DPAD_RIGHT)

        assertNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        assertEquals(UiSound.FOCUS, gate.complete(input))
        assertNull(gate.complete(input))
    }

    @Test fun repeatedMatchingReleasesDoNotCompleteOrDetachPendingFeedback() {
        val gate = UiSoundEventGate()
        val input = requireNotNull(gate.input(KEYCODE_DPAD_RIGHT, 100))
        repeat(2) { gate.releaseInput(KEYCODE_DPAD_RIGHT) }

        assertFalse(gate.accept(UiSound.VALUE))
        assertEquals(UiSound.VALUE, gate.complete(input))
        assertNull(gate.complete(input))
    }

    @Test fun releasePreservesTheValueSoundAlreadyAssignedToItsPress() {
        val gate = UiSoundEventGate()
        val input = requireNotNull(gate.input(KEYCODE_DPAD_RIGHT, 100))
        assertFalse(gate.accept(UiSound.VALUE))
        gate.releaseInput(KEYCODE_DPAD_RIGHT)

        assertEquals(UiSound.VALUE, gate.complete(input))
    }

    @Test fun valueAndBackReplaceTheDirectionTickInsteadOfAddingASecondSound() {
        for (kind in listOf(UiSound.VALUE, UiSound.BACK)) {
            val gate = UiSoundEventGate()
            val input = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))
            assertFalse(gate.accept(UiSound.FOCUS))
            assertFalse(gate.accept(kind))
            assertFalse(gate.accept(UiSound.FOCUS))

            assertEquals(kind, gate.complete(input))
            assertNull(gate.complete(input))
        }
    }

    @Test fun queuedPressesKeepTheirOwnSemanticSounds() {
        val gate = UiSoundEventGate()
        val first = requireNotNull(gate.input(KEYCODE_DPAD_DOWN, 100))
        val second = requireNotNull(gate.input(KEYCODE_DPAD_RIGHT, 101))
        assertFalse(gate.accept(UiSound.VALUE))
        val third = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 102))
        assertFalse(gate.accept(UiSound.BACK))

        assertEquals(UiSound.FOCUS, gate.complete(first))
        assertEquals(UiSound.VALUE, gate.complete(second))
        assertEquals(UiSound.BACK, gate.complete(third))
    }

    @Test fun completingAnOlderPressDoesNotDetachTheCurrentPress() {
        val gate = UiSoundEventGate()
        val first = requireNotNull(gate.input(KEYCODE_DPAD_DOWN, 100))
        val current = requireNotNull(gate.input(KEYCODE_DPAD_RIGHT, 101))

        assertEquals(UiSound.FOCUS, gate.complete(first))
        assertFalse(gate.accept(UiSound.VALUE))
        assertEquals(UiSound.VALUE, gate.complete(current))
    }

    @Test fun delayedFocusCannotDuplicateOrStealTheNextPress() {
        val gate = UiSoundEventGate()
        val first = requireNotNull(gate.input(KEYCODE_DPAD_DOWN, 100))
        assertFalse(gate.accept(UiSound.FOCUS))
        assertEquals(UiSound.FOCUS, gate.complete(first))
        assertFalse(gate.accept(UiSound.FOCUS))

        val next = requireNotNull(gate.input(KEYCODE_DPAD_DOWN, 101))
        // A late focus callback from the first scroll must not consume the new press.
        repeat(2) { assertFalse(gate.accept(UiSound.FOCUS)) }
        assertEquals(UiSound.FOCUS, gate.complete(next))
        assertFalse(gate.accept(UiSound.FOCUS))
    }

    @Test fun programmaticFocusStaysSilentAndIndependentClicksAreNotThrottled() {
        val gate = UiSoundEventGate()
        assertFalse(gate.accept(UiSound.FOCUS))
        repeat(3) { index ->
            assertNull(gate.input(KEYCODE_DPAD_CENTER, 100L + index))
            assertTrue(gate.accept(UiSound.SELECT))
            assertFalse(gate.accept(UiSound.FOCUS))
        }
        assertTrue(gate.accept(UiSound.BACK))
    }

    @Test fun nonDirectionalClickDoesNotReplaceAnEarlierQueuedDirectionSound() {
        val gate = UiSoundEventGate()
        val direction = requireNotNull(gate.input(KEYCODE_DPAD_DOWN, 100))
        assertNull(gate.input(KEYCODE_DPAD_CENTER, 101))

        assertTrue(gate.accept(UiSound.SELECT))
        assertEquals(UiSound.FOCUS, gate.complete(direction))
        assertFalse(gate.accept(UiSound.FOCUS))
    }

    @Test fun suppressionMustMatchBothTheKeyAndTimestamp() {
        val gate = UiSoundEventGate()
        val input = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        gate.suppressInput(KEYCODE_DPAD_RIGHT, 100)
        gate.suppressInput(KEYCODE_DPAD_LEFT, 101)

        assertEquals(UiSound.FOCUS, gate.complete(input))
    }

    @Test fun suppressedDirectionDoesNotMuteFollowingBackOrTheNextPress() {
        val gate = UiSoundEventGate()
        val muted = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        gate.suppressInput(KEYCODE_DPAD_LEFT, 100)
        assertNull(gate.input(KEYCODE_DPAD_LEFT, 100))
        assertFalse(gate.accept(UiSound.FOCUS))
        assertTrue(gate.accept(UiSound.BACK))
        assertNull(gate.complete(muted))
        assertNull(gate.complete(muted))

        val next = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 101))
        gate.suppressInput(KEYCODE_DPAD_LEFT, 100)
        assertEquals(UiSound.FOCUS, gate.complete(next))
    }

    @Test fun suppressedSelectorDirectionProducesOneBackRegardlessOfQueuedCompletionOrder() {
        for (completeBeforeBack in listOf(false, true)) {
            val gate = UiSoundEventGate()
            val heard = mutableListOf<UiSound>()
            val actions = UiSoundActions { kind -> if (gate.accept(kind)) heard += kind }
            val input = requireNotNull(gate.input(KEYCODE_DPAD_LEFT, 100))
            gate.suppressInput(KEYCODE_DPAD_LEFT, 100)

            if (completeBeforeBack) gate.complete(input)?.let(heard::add)
            // Companion can dispatch key-down and the selector's Back on key-up before the
            // main queue completes the suppressed direction. Both orders must sound once.
            actions.perform(UiSound.BACK) { actions.request(UiSound.BACK) }
            if (!completeBeforeBack) gate.complete(input)?.let(heard::add)

            assertEquals("completeBeforeBack=$completeBeforeBack", listOf(UiSound.BACK), heard)
            assertNull(gate.complete(input))
        }
    }
}
