package com.ambient.tv.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerIdleStateTest {
    @Test fun hoverAndWindowChangesDoNotWakeAStationaryMouse() {
        val state = PointerIdleState()
        assertFalse(state.visible)
        assertFalse(state.pointer(0, 100f, PointerIdleAction.HOVER_ENTER))
        assertFalse(state.pointer(10, 100f, PointerIdleAction.MOVE))
        assertTrue(state.pointer(20, 105f, PointerIdleAction.MOVE))
        assertTrue(state.visible)
        state.onNonPointerInput()
        assertFalse(state.pointer(30, 105f, PointerIdleAction.HOVER_EXIT))
        assertFalse(state.pointer(40, 105f, PointerIdleAction.HOVER_ENTER))
        assertFalse(state.pointer(50, 105f, PointerIdleAction.MOVE))
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
        assertTrue(state.pointer(60, 110f, PointerIdleAction.MOVE))
        assertTrue(state.visible)
    }

    @Test fun repeatedNonPointerInputRequiresNoVisibilityOrDeadlinePublication() {
        val state = PointerIdleState()
        repeat(1_000) { assertFalse(state.onNonPointerInput()) }
        assertTrue(state.pointer(1, 10f, PointerIdleAction.MOVE))
        assertTrue(state.onNonPointerInput())
        repeat(1_000) { assertFalse(state.onNonPointerInput()) }
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
        assertTrue(state.pointer(2, 20f, PointerIdleAction.BUTTON_PRESS))
        assertFalse(state.onNonPointerInput())
        assertTrue(state.visible)
        state.pointer(3, 20f, PointerIdleAction.BUTTON_RELEASE)
        assertTrue(state.onNonPointerInput())
        state.setTimeoutMillis(0, 4)
        assertFalse(state.onNonPointerInput())
        assertTrue(state.visible)
    }

    @Test fun newerActivityMakesAnAlreadyQueuedTimeoutHarmless() {
        val state = PointerIdleState()
        state.pointer(100, 10f, PointerIdleAction.MOVE)
        assertEquals(2_100L, state.deadlineMs)
        state.pointer(1_500, 20f, PointerIdleAction.MOVE)
        state.onTimeout(2_100)
        assertTrue(state.visible)
        assertEquals(3_500L, state.deadlineMs)
        state.onTimeout(3_499)
        assertTrue(state.visible)
        state.onTimeout(3_500)
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
    }

    @Test fun jitterNeitherExtendsTheDeadlineNorPreventsAccumulatedMovement() {
        val state = PointerIdleState()
        state.pointer(0, 100f, PointerIdleAction.MOVE)
        assertFalse(state.pointer(1_000, 100.4f, PointerIdleAction.MOVE))
        assertFalse(state.pointer(1_500, 100.8f, PointerIdleAction.MOVE))
        assertEquals(2_000L, state.deadlineMs)
        state.onTimeout(2_000)
        assertFalse(state.visible)
        assertTrue(state.pointer(2_010, 101.2f, PointerIdleAction.MOVE))
        assertTrue(state.visible)
    }

    @Test fun heldMouseButtonsSuspendTimeoutAndKeyboardHidingUntilRelease() {
        val state = PointerIdleState()
        assertTrue(state.pointer(0, 10f, PointerIdleAction.BUTTON_PRESS))
        assertNull(state.deadlineMs)
        state.onTimeout(20_000)
        state.onNonPointerInput()
        assertTrue(state.visible)
        state.pointer(20_010, 10f, PointerIdleAction.BUTTON_RELEASE, buttonsDown = true)
        assertNull(state.deadlineMs)
        state.pointer(20_020, 10f, PointerIdleAction.BUTTON_RELEASE)
        assertEquals(22_020L, state.deadlineMs)
        state.onTimeout(22_020)
        assertFalse(state.visible)
    }

    @Test fun multiplePointerSourcesKeepTheirOwnAnchorsAndButtonHolds() {
        val state = PointerIdleState()
        state.pointer(0, 10f, PointerIdleAction.BUTTON_PRESS, sourceId = 1)
        state.pointer(10, 100f, PointerIdleAction.BUTTON_PRESS, sourceId = 2)
        state.pointer(20, 10f, PointerIdleAction.BUTTON_RELEASE, sourceId = 1)
        assertNull(state.deadlineMs)
        state.pointer(30, 100f, PointerIdleAction.BUTTON_RELEASE, sourceId = 2)
        assertEquals(2_030L, state.deadlineMs)
        state.onTimeout(2_030)
        assertFalse(state.pointer(2_040, 10f, PointerIdleAction.MOVE, sourceId = 1))
        assertFalse(state.pointer(2_050, 100f, PointerIdleAction.MOVE, sourceId = 2))
        assertFalse(state.pointer(2_060, 300f, PointerIdleAction.HOVER_ENTER, sourceId = 3))
        assertFalse(state.pointer(2_070, 300f, PointerIdleAction.MOVE, sourceId = 3))
        assertFalse(state.visible)
        assertTrue(state.pointer(2_080, 110f, PointerIdleAction.MOVE, sourceId = 2))
    }

    @Test fun focusLossCancelsHeldButtonsAndIgnoresTheirTrailingRelease() {
        val state = PointerIdleState()
        state.pointer(0, 10f, PointerIdleAction.BUTTON_PRESS)
        state.onFocusLost()
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
        assertFalse(state.pointer(10, 10f, PointerIdleAction.BUTTON_RELEASE))
        assertFalse(state.pointer(20, 10f, PointerIdleAction.HOVER_ENTER))
        assertFalse(state.visible)
        assertTrue(state.pointer(30, 10f, PointerIdleAction.SCROLL))
        assertEquals(2_030L, state.deadlineMs)
    }

    @Test fun cancellationReleasesWithoutWakingOrKeepingThePointerVisibleForever() {
        val state = PointerIdleState()
        state.pointer(0, 10f, PointerIdleAction.BUTTON_PRESS)
        assertFalse(state.pointer(100, Float.NaN, PointerIdleAction.CANCEL))
        assertEquals(2_100L, state.deadlineMs)
        state.onTimeout(2_100)
        assertFalse(state.pointer(2_200, 10f, PointerIdleAction.CANCEL))
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
    }

    @Test fun ownWindowFocusTransfersPreserveIdleDeadlineAndHiddenState() {
        val state = PointerIdleState()
        state.pointer(100, 10f, PointerIdleAction.MOVE)
        state.onWindowFocusLost(500)
        assertTrue(state.visible)
        assertEquals(2_100L, state.deadlineMs)
        state.onWindowFocusLost(1_000)
        assertEquals(2_100L, state.deadlineMs)
        state.onTimeout(2_100)
        state.onWindowFocusLost(3_000)
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
        assertFalse(state.pointer(3_100, 10f, PointerIdleAction.HOVER_ENTER))
        assertFalse(state.pointer(3_200, 10f, PointerIdleAction.MOVE))
    }

    @Test fun ownWindowFocusTransfersReleaseEveryHoldAndStartOneIdlePeriod() {
        val state = PointerIdleState()
        state.pointer(0, 10f, PointerIdleAction.BUTTON_PRESS, sourceId = 1)
        state.pointer(10, 20f, PointerIdleAction.BUTTON_PRESS, sourceId = 2)
        state.onWindowFocusLost(100)
        assertTrue(state.visible)
        assertEquals(2_100L, state.deadlineMs)
        state.onWindowFocusLost(200)
        assertEquals(2_100L, state.deadlineMs)
        state.onTimeout(2_100)
        assertFalse(state.visible)
    }

    @Test fun aStationaryMoveCanRecoverFromAMissingButtonUpWithoutClaimingActivity() {
        val state = PointerIdleState()
        state.pointer(0, 10f, PointerIdleAction.BUTTON_PRESS)
        assertFalse(state.pointer(100, 10f, PointerIdleAction.MOVE, buttonsDown = true))
        assertNull(state.deadlineMs)
        assertFalse(state.pointer(200, 10f, PointerIdleAction.MOVE))
        assertEquals(2_200L, state.deadlineMs)
        state.onTimeout(2_200)
        assertFalse(state.visible)
    }

    @Test fun offLeavesNativePointerVisibleAndEnablingWithoutActivityKeepsItHidden() {
        val state = PointerIdleState(timeoutMillis = 0)
        assertTrue(state.visible)
        state.onNonPointerInput()
        state.onTimeout(Long.MAX_VALUE)
        state.onFocusLost()
        assertTrue(state.visible)
        assertNull(state.deadlineMs)
        state.setTimeoutMillis(2_000, 100)
        assertFalse(state.visible)
        state.pointer(200, 10f, PointerIdleAction.SCROLL)
        state.setTimeoutMillis(0, 300)
        state.onTimeout(20_000)
        assertTrue(state.visible)
        assertNull(state.deadlineMs)
        state.setTimeoutMillis(1_000, 20_000)
        assertFalse(state.visible)
    }

    @Test fun changingTimeoutUsesActualActivityAndNeverResurrectsAHiddenPointer() {
        val state = PointerIdleState()
        state.pointer(100, 10f, PointerIdleAction.MOVE)
        state.setTimeoutMillis(5_000, 500)
        assertEquals(5_100L, state.deadlineMs)
        state.setTimeoutMillis(1_000, 1_500)
        assertFalse(state.visible)
        state.setTimeoutMillis(5_000, 1_600)
        assertFalse(state.visible)
        assertNull(state.deadlineMs)
    }

    @Test fun invalidCoordinatesDoNotPoisonPositionsOrWakeThePointer() {
        val state = PointerIdleState()
        assertFalse(state.pointer(0, Float.NaN, PointerIdleAction.MOVE))
        assertFalse(state.pointer(10, Float.POSITIVE_INFINITY, PointerIdleAction.BUTTON_PRESS))
        assertFalse(state.visible)
        assertTrue(state.pointer(20, 10f, PointerIdleAction.MOVE))
        assertEquals(2_020L, state.deadlineMs)
    }

    private fun PointerIdleState.pointer(
        nowMs: Long,
        x: Float,
        action: PointerIdleAction,
        buttonsDown: Boolean = false,
        sourceId: Int = 0,
    ): Boolean = onPointer(nowMs, x, 20f, action, buttonsDown, sourceId)
}
