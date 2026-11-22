package com.ambient.tv.companion

import android.app.Application
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.gamepad.GameInputMode
import com.ambient.tv.ui.gamepad.GamepadConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompanionGamepadMenuInputTest {
    private class Clock {
        class Task(val due: Long, val work: () -> Unit) { var cancelled = false }
        var now = 1_000L
        private val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, work: () -> Unit): () -> Unit {
            val task = Task(now + delay, work)
            tasks += task
            return { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
                tasks -= task
                now = task.due
                task.work()
            }
            now = end
        }
    }

    private val clock = Clock()
    private val sent = mutableListOf<String>()
    private val gameplay = mutableListOf<Int>()
    private var config = GamepadConfig()
    private val input = CompanionGamepadMenuInput(
        gameInput = { if (it is KeyEvent) gameplay += it.keyCode; true },
        navigate = { action, _, _ -> sent += action }, resume = { _, _ -> sent += "resume" },
        confirm = { _, held, cancelled, _, _ -> sent += if (held) "ok_down" else if (cancelled) "ok_cancel" else "ok_up" },
        scheduler = clock::schedule, clock = { clock.now }, config = { config },
        physicalEvent = { true },
    )

    private fun key(code: Int, down: Boolean, repeat: Int = 0): KeyEvent =
        KeyEvent(clock.now, clock.now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, 7, 0, 0, InputDevice.SOURCE_GAMEPAD)
    private fun tap(code: Int) {
        input.onEvent(key(code, true))
        clock.advance(10)
        input.onEvent(key(code, false))
    }
    private fun stick(x: Float, y: Float) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, x); setAxisValue(MotionEvent.AXIS_Y, y)
        })
        val event = MotionEvent.obtain(clock.now, clock.now, MotionEvent.ACTION_MOVE, 1,
            properties, coords, 0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_JOYSTICK, 0)
        try { assertTrue(input.onEvent(event)) } finally { event.recycle() }
    }

    @Test fun sameControllerPlaysAndThenNavigatesTheTvWithoutPhoneAppActions() {
        input.update(GameInputMode.RUNNING, 1)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(2, gameplay.size)
        assertTrue(sent.isEmpty())
        input.update(GameInputMode.MENU, 2)
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_BUTTON_B)
        tap(KeyEvent.KEYCODE_BUTTON_MODE)
        tap(KeyEvent.KEYCODE_BUTTON_Y)
        tap(KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(listOf("down", "ok_down", "ok_up", "back", "resume"), sent)
        assertEquals(2, gameplay.size)
    }

    @Test fun nintendoConfirmSideIsPreservedAndBlockedStatesConsumeControllerKeys() {
        config = config.copy(confirmOnB = true)
        input.update(GameInputMode.DIALOG, 1)
        tap(KeyEvent.KEYCODE_BUTTON_B)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("ok_down", "ok_up", "back"), sent)
        sent.clear()
        input.update(GameInputMode.BLOCKED, 2)
        tap(KeyEvent.KEYCODE_BUTTON_MODE)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        tap(KeyEvent.KEYCODE_BACK)
        assertTrue(sent.isEmpty())
        assertTrue(gameplay.isEmpty())
    }

    @Test fun heldGameplayButtonMustBeReleasedBeforeBecomingAMenuAction() {
        input.update(GameInputMode.RUNNING, 1)
        input.onEvent(key(KeyEvent.KEYCODE_BUTTON_A, true))
        input.update(GameInputMode.MENU, 2)
        input.onEvent(key(KeyEvent.KEYCODE_BUTTON_A, true, repeat = 1))
        input.onEvent(key(KeyEvent.KEYCODE_BUTTON_A, false))
        assertTrue(sent.isEmpty())
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("ok_down", "ok_up"), sent)
    }

    @Test fun gameIdentityChangeAlsoRequiresHeldControlsToBeReleased() {
        input.update(GameInputMode.RUNNING, 1, "first")
        input.onEvent(key(KeyEvent.KEYCODE_BUTTON_A, true))
        input.update(GameInputMode.RUNNING, 1, "second")
        input.onEvent(key(KeyEvent.KEYCODE_BUTTON_A, true, repeat = 1))
        input.onEvent(key(KeyEvent.KEYCODE_BUTTON_A, false))
        assertEquals(1, gameplay.size)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(3, gameplay.size)
    }

    @Test fun confirmBackAndResumeDoNotRepeatThroughSeveralDialogs() {
        input.update(GameInputMode.MENU, 1)
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_MODE)) {
            input.onEvent(key(code, true))
            clock.advance(1_500)
            input.onEvent(key(code, false))
        }
        assertEquals(listOf("ok_down", "ok_up", "back", "resume"), sent)
    }

    @Test fun menuStickRepeatsStopWhenContextChangesAndRequireNeutralBeforeContinuing() {
        input.update(GameInputMode.MENU, 1)
        stick(0f, 1f)
        clock.advance(600)
        assertTrue(sent.size >= 2)
        val previous = sent.size
        input.update(GameInputMode.DIALOG, 2)
        clock.advance(1_000)
        stick(0f, 1f)
        assertEquals(previous, sent.size)
        stick(0f, 0f)
        stick(0f, -1f)
        assertEquals("up", sent.last())
        input.releaseAll()
        val released = sent.size
        clock.advance(1_000)
        assertEquals(released, sent.size)
    }
}
