package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test
import com.ambient.tv.ui.common.ScreensaverInputPhase.*
import com.ambient.tv.ui.common.ScreensaverInputResult.*

class ScreensaverInputGateTest {
    @Test fun releasingTheTestButtonDoesNotDismissThePreview() {
        val gate = ScreensaverInputGate()
        assertEquals(PASS, gate.key(23, DOWN, 0, false, false))
        // Test opens from the DOWN callback; UP still belongs to that same gesture.
        assertEquals(CONSUME, gate.key(23, UP, 0, true, true))
        assertEquals(DISMISS, gate.key(23, DOWN, 0, true, true))
    }

    @Test fun holdingTheTestButtonDoesNotDismissOrRestartIt() {
        val gate = ScreensaverInputGate()
        gate.key(23, DOWN, 0, false, false)
        for (repeat in 1..30) assertEquals(CONSUME, gate.key(23, DOWN, repeat, true, true))
        assertEquals(CONSUME, gate.key(23, UP, 0, true, true))
        assertEquals(DISMISS, gate.key(23, DOWN, 0, true, true))
    }

    @Test fun repeatedDownWithoutARepeatFlagIsStillTheOriginalGesture() {
        val gate = ScreensaverInputGate()
        gate.key(23, DOWN, 0, false, false)
        assertEquals(CONSUME, gate.key(23, DOWN, 0, true, true))
    }

    @Test fun anotherKeyCanDismissWhileTheOpeningKeyIsHeld() {
        val gate = ScreensaverInputGate()
        gate.key(23, DOWN, 0, false, false)
        assertEquals(DISMISS, gate.key(4, DOWN, 0, true, true))
        assertEquals(CONSUME, gate.key(23, UP, 0, false, false))
        assertEquals(CONSUME, gate.key(4, UP, 0, false, false))
    }

    @Test fun wakeKeyUpCannotClickTheControlUnderTheOverlay() {
        val gate = ScreensaverInputGate()
        assertEquals(DISMISS, gate.key(23, DOWN, 0, true, true))
        assertEquals(CONSUME, gate.key(23, DOWN, 3, false, false))
        assertEquals(CONSUME, gate.key(23, UP, 0, false, false))
        assertEquals(PASS, gate.key(23, DOWN, 0, false, false))
        assertEquals(PASS, gate.key(23, UP, 0, false, false))
    }

    @Test fun additionalKeysDuringFadeOutAreFullyConsumed() {
        val gate = ScreensaverInputGate()
        gate.key(4, DOWN, 0, true, true)
        assertEquals(CONSUME, gate.key(23, DOWN, 0, false, true))
        assertEquals(CONSUME, gate.key(23, UP, 0, false, false))
        assertEquals(CONSUME, gate.key(4, UP, 0, false, false))
    }

    @Test fun repeatAndUnmatchedReleaseCannotDismissAnAutomaticScreensaver() {
        val gate = ScreensaverInputGate()
        assertEquals(CONSUME, gate.key(23, UP, 0, true, true))
        assertEquals(CONSUME, gate.key(23, DOWN, 1, true, true))
        assertEquals(CONSUME, gate.key(23, UP, 0, true, true))
        assertEquals(DISMISS, gate.key(23, DOWN, 0, true, true))
    }

    @Test fun twoRemotesHaveIndependentKeyIdentities() {
        val gate = ScreensaverInputGate()
        gate.key((1L shl 32) or 23, DOWN, 0, false, false)
        assertEquals(DISMISS, gate.key((2L shl 32) or 23, DOWN, 0, true, true))
    }

    @Test fun touchOpeningGestureAndWakeGestureAreIndependent() {
        val gate = ScreensaverInputGate()
        assertEquals(PASS, gate.touch(DOWN, false, false))
        assertEquals(CONSUME, gate.touch(OTHER, true, true))
        assertEquals(CONSUME, gate.touch(UP, true, true))
        assertEquals(DISMISS, gate.touch(DOWN, true, true))
        assertEquals(CONSUME, gate.touch(UP, false, false))
        assertEquals(PASS, gate.touch(DOWN, false, false))
    }

    @Test fun canceledTouchDoesNotBlockNextInteraction() {
        val gate = ScreensaverInputGate()
        gate.touch(DOWN, true, true)
        assertEquals(CONSUME, gate.touch(CANCEL, false, true))
        assertEquals(PASS, gate.touch(DOWN, false, false))
    }

    @Test fun lifecycleResetClearsHeldKeysAndTouches() {
        val gate = ScreensaverInputGate()
        gate.key(23, DOWN, 0, true, true)
        gate.touch(DOWN, true, true)
        gate.reset()
        assertEquals(PASS, gate.key(23, DOWN, 0, false, false))
        assertEquals(PASS, gate.touch(DOWN, false, false))
    }
}
