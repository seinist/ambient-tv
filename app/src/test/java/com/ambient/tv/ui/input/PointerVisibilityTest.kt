package com.ambient.tv.ui.input

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Android input sources and the main-looper clock exercise the shared coordinator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PointerVisibilityTest {
    private val owner = Any()
    private val owners = mutableListOf(owner)
    private val subscriptions = mutableListOf<() -> Unit>()
    private val mouseDeviceId = deviceIds.incrementAndGet()

    @Before fun setUp() {
        PointerVisibility.start(owner, 2)
    }

    @After fun tearDown() {
        subscriptions.forEach { it() }
        PointerVisibility.configure(2)
        owners.asReversed().forEach(PointerVisibility::stop)
    }

    @Test fun onlyRealMouseActivityWakesThePointerIncludingFingerToolTrackpads() {
        assertTrue(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_HOVER_ENTER)
        motion(MotionEvent.ACTION_HOVER_EXIT)
        motion(MotionEvent.ACTION_HOVER_ENTER, x = 500f) // A new window is not movement.
        motion(MotionEvent.ACTION_MOVE, x = 600f,
            source = InputDevice.SOURCE_TOUCHSCREEN, toolType = MotionEvent.TOOL_TYPE_FINGER)
        motion(MotionEvent.ACTION_SCROLL) // Drivers may report an empty scroll event.
        assertTrue(PointerVisibility.hideNativePointer)

        // Touchpads use SOURCE_MOUSE even when Android describes the contact as a finger.
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 120f, toolType = MotionEvent.TOOL_TYPE_FINGER)
        assertFalse(PointerVisibility.hideNativePointer)
        assertFalse(PointerVisibility.companionVisible)
        advance(2_000)
        assertTrue(PointerVisibility.hideNativePointer)
    }

    @Test fun laterMovementExtendsTheDeadlineWhenAnEarlierCallbackIsAlreadyQueued() {
        val changes = observeNative()
        motion(MotionEvent.ACTION_HOVER_MOVE)
        advance(1_500)
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 150f)
        advance(500) // The callback for the first movement must check the newer deadline.
        assertFalse(PointerVisibility.hideNativePointer)
        advance(1_499)
        assertFalse(PointerVisibility.hideNativePointer)
        advance(1)
        assertTrue(PointerVisibility.hideNativePointer)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test fun idleHoverAndTouchCannotExtendTheDeadlineOrResurrectAnExpiredCursor() {
        motion(MotionEvent.ACTION_HOVER_MOVE)
        advance(1_000)
        motion(MotionEvent.ACTION_HOVER_MOVE) // Same position, later event timestamp.
        motion(MotionEvent.ACTION_HOVER_ENTER, x = 700f)
        motion(MotionEvent.ACTION_MOVE, x = 800f, source = InputDevice.SOURCE_TOUCHSCREEN)
        advance(1_000)
        assertTrue(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_HOVER_ENTER, x = 900f)
        motion(MotionEvent.ACTION_HOVER_MOVE) // Still the original real mouse position.
        assertTrue(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_SCROLL, scrollY = 1f)
        assertFalse(PointerVisibility.hideNativePointer)
    }

    @Test fun heldMouseDragSurvivesIdleAndPartialButtonReleaseUntilTheFinalRelease() {
        motion(MotionEvent.ACTION_DOWN, buttons = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY)
        advance(10_000)
        assertFalse(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_MOVE, x = 180f, buttons = MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY)
        motion(MotionEvent.ACTION_BUTTON_RELEASE, x = 180f, buttons = MotionEvent.BUTTON_PRIMARY)
        advance(10_000)
        assertFalse(PointerVisibility.hideNativePointer)

        motion(MotionEvent.ACTION_UP, x = 180f)
        advance(1_999)
        assertFalse(PointerVisibility.hideNativePointer)
        advance(1)
        assertTrue(PointerVisibility.hideNativePointer)
    }

    @Test fun transferringWindowFocusReleasesAHoldWithoutFlashingOrStrandingTheCursor() {
        motion(MotionEvent.ACTION_DOWN, buttons = MotionEvent.BUTTON_PRIMARY)
        advance(1_000)
        PointerVisibility.onWindowFocusLost()
        assertFalse(PointerVisibility.hideNativePointer)
        advance(2_000)
        assertTrue(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_UP) // Late release from the old window must stay hidden.
        assertTrue(PointerVisibility.hideNativePointer)
    }

    @Test fun keyNavigationHidesButMouseKeysCanceledKeysAndKeyReleasesDoNot() {
        motion(MotionEvent.ACTION_HOVER_MOVE)
        PointerVisibility.onKeyEvent(key(source = InputDevice.SOURCE_MOUSE))
        PointerVisibility.onKeyEvent(key(flags = KeyEvent.FLAG_CANCELED))
        PointerVisibility.onKeyEvent(key(action = KeyEvent.ACTION_UP))
        assertFalse(PointerVisibility.hideNativePointer)
        PointerVisibility.onKeyEvent(key())
        assertTrue(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_HOVER_MOVE)
        assertTrue(PointerVisibility.hideNativePointer)
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 140f)
        assertFalse(PointerVisibility.hideNativePointer)
    }

    @Test fun changingTimeoutUsesTheLastActivityAndOffDisablesIdleHiding() {
        motion(MotionEvent.ACTION_HOVER_MOVE)
        advance(1_000)
        PointerVisibility.configure(5)
        advance(3_999)
        assertFalse(PointerVisibility.hideNativePointer)
        advance(1)
        assertTrue(PointerVisibility.hideNativePointer)

        motion(MotionEvent.ACTION_HOVER_MOVE, x = 140f)
        advance(1_500)
        PointerVisibility.configure(1) // The new deadline has already elapsed.
        assertTrue(PointerVisibility.hideNativePointer)
        PointerVisibility.configure(0)
        assertFalse(PointerVisibility.hideNativePointer)
        advance(60_000)
        PointerVisibility.onKeyEvent(key())
        assertFalse(PointerVisibility.hideNativePointer)
        PointerVisibility.configure(2)
        assertTrue(PointerVisibility.hideNativePointer)
    }

    @Test fun companionEventsHaveOneCursorAndNativeHoverCannotStealOwnership() {
        motion(MotionEvent.ACTION_HOVER_MOVE)
        PointerVisibility.onCompanionInput("move", .4f, .5f, moved = true)
        assertTrue(PointerVisibility.hideNativePointer)
        assertTrue(PointerVisibility.companionVisible)

        // The synthetic companion hover travels through the same native root observers.
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 700f, deviceId = PointerVisibility.COMPANION_DEVICE_ID)
        motion(MotionEvent.ACTION_HOVER_ENTER, x = 800f)
        assertTrue(PointerVisibility.hideNativePointer)
        assertTrue(PointerVisibility.companionVisible)
        advance(2_000)
        assertFalse(PointerVisibility.companionVisible)
        assertTrue(PointerVisibility.hideNativePointer)

        PointerVisibility.onCompanionInput("click", .4f, .5f, moved = false)
        assertTrue(PointerVisibility.companionVisible)
        // Atomic companion down/up must not leave a held button that suppresses expiry.
        advance(2_000)
        assertFalse(PointerVisibility.companionVisible)
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 160f)
        assertFalse(PointerVisibility.hideNativePointer)
        assertFalse(PointerVisibility.companionVisible)
    }

    @Test fun zeroMovementCompanionHoverDoesNotWakeEitherPointer() {
        PointerVisibility.onCompanionInput("move", .25f, .75f, moved = false)
        assertTrue(PointerVisibility.hideNativePointer)
        assertFalse(PointerVisibility.companionVisible)
        PointerVisibility.onCompanionInput("move", .30f, .75f, moved = true)
        assertTrue(PointerVisibility.companionVisible)
        advance(1_000)
        PointerVisibility.onCompanionInput("move", .30f, .75f, moved = false)
        advance(1_000)
        assertFalse(PointerVisibility.companionVisible)
    }

    @Test fun anotherForegroundOwnerKeepsTheDeadlineAndStoppingTheLastOwnerCancelsIt() {
        val secondOwner = Any().also { owners += it }
        PointerVisibility.start(secondOwner, 2)
        motion(MotionEvent.ACTION_HOVER_MOVE)
        PointerVisibility.stop(owner)
        advance(1_999)
        assertFalse(PointerVisibility.hideNativePointer)
        advance(1)
        assertTrue(PointerVisibility.hideNativePointer)

        motion(MotionEvent.ACTION_HOVER_MOVE, x = 140f)
        PointerVisibility.stop(secondOwner)
        assertTrue(PointerVisibility.hideNativePointer)
        advance(500)
        PointerVisibility.start(owner, 2)
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 180f)
        advance(1_999)
        assertFalse(PointerVisibility.hideNativePointer)
        advance(1)
        assertTrue(PointerVisibility.hideNativePointer)
    }

    @Test fun nativeObserversOnlySeeVisibilityChangesAndCanUnsubscribe() {
        val changes = mutableListOf<Boolean>()
        val unsubscribe = PointerVisibility.observeNativeVisibility { changes += it }.also(subscriptions::add)
        motion(MotionEvent.ACTION_HOVER_MOVE)
        motion(MotionEvent.ACTION_HOVER_MOVE, x = 140f)
        assertEquals(listOf(true, false), changes)
        unsubscribe()
        advance(2_000)
        assertTrue(PointerVisibility.hideNativePointer)
        assertEquals(listOf(true, false), changes)
    }

    private fun observeNative(): MutableList<Boolean> = mutableListOf<Boolean>().also { changes ->
        subscriptions += PointerVisibility.observeNativeVisibility { changes += it }
    }

    private fun advance(milliseconds: Long) =
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(milliseconds, TimeUnit.MILLISECONDS)

    private fun key(
        action: Int = KeyEvent.ACTION_DOWN,
        source: Int = InputDevice.SOURCE_DPAD,
        flags: Int = 0,
    ): KeyEvent = SystemClock.uptimeMillis().let { now ->
        KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0, 0, mouseDeviceId, 0, flags, source)
    }

    private fun motion(
        action: Int,
        x: Float = 100f,
        y: Float = 200f,
        source: Int = InputDevice.SOURCE_MOUSE,
        toolType: Int = MotionEvent.TOOL_TYPE_MOUSE,
        buttons: Int = 0,
        scrollX: Float = 0f,
        scrollY: Float = 0f,
        deviceId: Int = mouseDeviceId,
    ) {
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, scrollX)
            setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY)
        }
        val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, buttons, 1f, 1f, deviceId, 0, source, 0)
        try {
            PointerVisibility.onMotionEvent(event)
            // Observation must not rewrite an event before the target receives it.
            assertEquals(source, event.source)
            assertEquals(action, event.action)
            assertEquals(buttons, event.buttonState)
            assertEquals(x, event.x, 0f)
            assertEquals(y, event.y, 0f)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        val deviceIds = AtomicInteger(100)
    }
}
