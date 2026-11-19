package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GamepadInputTest {
    private class FakeHost : GamepadHost {
        data class Sent(val action: Int, val keyCode: Int, val repeat: Int, val canceled: Boolean)
        private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }

        val sent = mutableListOf<Sent>()
        val performed = mutableListOf<KeyAction>()
        val volumes = mutableListOf<GamepadVolumeCommand>()
        override var playerForeground = false
        override var recordingKey = false
        var time = 1_000L
        private val tasks = mutableListOf<Task>()

        override fun emitKey(event: KeyEvent) {
            sent += Sent(event.action, event.keyCode, event.repeatCount, event.isCanceled)
        }
        override fun perform(action: KeyAction) { performed += action }
        override fun volume(command: GamepadVolumeCommand) { volumes += command }
        override fun now() = time
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = Task(time + delayMs, task)
            tasks += entry
            return { entry.cancelled = true }
        }

        fun advance(ms: Long) {
            val end = time + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks -= next
                time = next.at
                next.run()
            }
            time = end
        }

        fun downs(code: Int) = sent.count { it.action == KeyEvent.ACTION_DOWN && it.keyCode == code }
    }

    private val host = FakeHost()
    private var config = GamepadConfig()
    private val pressed = mutableListOf<GamepadButton>()
    private var sink: ((android.view.InputEvent) -> Boolean)? = null
    private val controller = GamepadController(host, { config }, { pressed += it }, { sink })

    private fun key(action: Int, code: Int, repeat: Int = 0, device: Int = 7) =
        KeyEvent(host.time, host.time, action, code, repeat, 0, device, 0, 0, InputDevice.SOURCE_GAMEPAD)

    private fun tap(code: Int, holdMs: Long = 50) {
        controller.handleKey(key(KeyEvent.ACTION_DOWN, code))
        host.advance(holdMs)
        controller.handleKey(key(KeyEvent.ACTION_UP, code))
    }

    private fun stick(device: Int = 7, values: Map<Int, Float>): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { values.forEach { (axis, value) -> setAxisValue(axis, value) } })
        return MotionEvent.obtain(host.time, host.time, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0,
            1f, 1f, device, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    @Test fun aTapIsOneConfirmPress() {
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(
            FakeHost.Sent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, false),
            FakeHost.Sent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, false),
        ), host.sent)
        assertEquals(listOf(GamepadButton.A), pressed)
    }

    @Test fun aHeldButtonRepeatsItsKeyLikeAHeldRemoteKey() {
        controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1))
        // System repeats are ignored; the layer repeats on its own.
        controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, repeat = 1))
        host.advance(KEY_REPEAT_TIMEOUT_MS + 2 * KEY_REPEAT_INTERVAL_MS)
        controller.handleKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(listOf(0, 1, 2, 3), host.sent.filter { it.action == KeyEvent.ACTION_DOWN }.map { it.repeat })
        assertTrue(host.sent.all { it.keyCode == KeyEvent.KEYCODE_PAGE_UP })
        assertEquals(KeyEvent.ACTION_UP, host.sent.last().action)
    }

    @Test fun aButtonWithALongPressDecidesOnReleaseOrAfterTheThreshold() {
        tap(KeyEvent.KEYCODE_BUTTON_START, holdMs = 100)
        assertEquals(listOf(KeyAction.SIDEBAR), host.performed)
        host.performed.clear()
        tap(KeyEvent.KEYCODE_BUTTON_START, holdMs = 800)
        assertEquals(listOf(KeyAction.SETTINGS), host.performed)
        assertTrue(host.sent.isEmpty())
    }

    @Test fun aShortKeyCommandOnALongPressButtonIsSentAsATapOnRelease() {
        config = config.withCommand(GamepadContext.MENUS, GamepadSlot(GamepadButton.X, true), GamepadCommand.Action(KeyAction.REFRESH))
        controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X))
        assertTrue(host.sent.isEmpty())
        host.advance(100)
        controller.handleKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), host.sent.map { it.action })
        assertTrue(host.sent.all { it.keyCode == KeyEvent.KEYCODE_MENU })
        assertTrue(host.performed.isEmpty())
    }

    @Test fun thePlayerUsesItsOwnLayer() {
        host.playerForeground = true
        tap(KeyEvent.KEYCODE_BUTTON_X)
        tap(KeyEvent.KEYCODE_BUTTON_Y, holdMs = 100)
        assertEquals(1, host.downs(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf(KeyAction.SUBTITLES), host.performed)
    }

    @Test fun recordingARemoteKeyOnlySendsKeys() {
        host.recordingKey = true
        tap(KeyEvent.KEYCODE_BUTTON_Y)
        tap(KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(host.performed.isEmpty())
        assertEquals(1, host.downs(KeyEvent.KEYCODE_BACK))
    }

    @Test fun volumeCommandsUseTheSystemVolume() {
        config = config.withCommand(GamepadContext.MENUS, GamepadSlot(GamepadButton.THUMB_R, false), GamepadCommand.Volume(GamepadVolumeCommand.UP))
        tap(KeyEvent.KEYCODE_BUTTON_THUMBR)
        assertEquals(listOf(GamepadVolumeCommand.UP), host.volumes)
    }

    @Test fun disabledOrOtherKeysTakeTheirNormalWay() {
        assertFalse(controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertFalse(controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_C)))
        config = config.copy(enabled = false)
        assertFalse(controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertFalse(controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_X to 1f))))
        assertTrue(host.sent.isEmpty())
    }

    @Test fun aGameInFrontGetsRawInputFirst() {
        val raw = mutableListOf<Int>()
        sink = { event -> (event as? KeyEvent)?.let { raw += it.keyCode }; true }
        assertTrue(controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_A), raw)
        assertTrue(host.sent.isEmpty())
    }

    @Test fun releaseAllCancelsHeldKeys() {
        controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        controller.releaseAll()
        assertEquals(FakeHost.Sent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, true), host.sent.last())
        host.advance(2_000)
        assertEquals(1, host.downs(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun theLeftStickMovesTheSelectionWithRepeatAndHysteresis() {
        assertTrue(controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_X to 0.8f))))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_DPAD_RIGHT))
        host.advance(GamepadRepeatSpeed.NORMAL.initialDelayMs)
        assertEquals(2, host.downs(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Below the activation but above the release point: still held.
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_X to 0.4f)))
        assertFalse(host.sent.any { it.action == KeyEvent.ACTION_UP })
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_X to 0.1f)))
        assertEquals(KeyEvent.ACTION_UP, host.sent.last().action)
        val count = host.sent.size
        host.advance(5_000)
        assertEquals(count, host.sent.size)
    }

    @Test fun theHatAlwaysNavigatesAndTheLeftStickCanBeTurnedOff() {
        config = config.copy(leftStickNavigates = false)
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_Y to -1f)))
        assertTrue(host.sent.isEmpty())
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_HAT_Y to -1f)))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun theRightStickPagesAndWindsOnlyInThePlayer() {
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_RZ to 1f)))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_PAGE_DOWN))
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_Z to 1f)))
        assertEquals(0, host.downs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        controller.handleMotion(stick(values = emptyMap()))
        host.playerForeground = true
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_Z to 1f)))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test fun triggerAxesActAsButtonsUntilTheControllerSendsTriggerKeys() {
        host.playerForeground = true
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_RTRIGGER to 1f)))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        // The same pull also arrives as a key: dropped.
        assertTrue(controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2)))
        assertTrue(controller.handleKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2)))
        controller.handleMotion(stick(values = emptyMap()))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(KeyEvent.ACTION_UP, host.sent.last().action)
        // From now on the keys count and the axis is ignored.
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_RTRIGGER to 1f)))
        assertEquals(1, host.downs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        tap(KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(2, host.downs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test fun aDisconnectedControllerLetsGoOfEverything() {
        controller.handleKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        controller.handleMotion(stick(values = mapOf(MotionEvent.AXIS_X to -1f)))
        controller.deviceRemoved(7)
        assertEquals(2, host.sent.count { it.action == KeyEvent.ACTION_UP && it.canceled })
    }
}
