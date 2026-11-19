package com.ambient.tv.ui.gamepad.retro

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.gamepad.GamepadAxisLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RetroPadInputTest {
    private var time = 0L
    private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
    private var menus = 0
    private val rewinds = mutableListOf<Boolean>()
    private var fastForwards = 0
    private var config = RetroInputConfig()
    private val kinds = mapOf(
        1 to RetroInputKind.GAMEPAD, 2 to RetroInputKind.GAMEPAD,
        3 to RetroInputKind.KEYBOARD, 4 to RetroInputKind.REMOTE,
    )
    private val input = RetroPadInput(
        config = { config },
        device = { id -> RetroInputDevice(id, kinds.getValue(id), "dev-$id", "Device $id") },
        schedule = { delay, task ->
            val cancelled = BooleanArray(1)
            tasks += Triple(time + delay, task, cancelled)
            ({ cancelled[0] = true })
        },
        axisLayout = { GamepadAxisLayout.STANDARD },
        onMenu = { menus++ },
        onRewind = { rewinds += it },
        onFastForward = { fastForwards++ },
    )

    private fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = tasks.filter { !it.third[0] && it.first <= end }.minByOrNull { it.first } ?: break
            tasks -= next
            time = next.first
            next.second()
        }
        time = end
    }

    private fun key(device: Int, action: Int, code: Int) = input.onKey(KeyEvent(0, time, action, code, 0, 0, device, 0))
    private fun press(device: Int, code: Int) = key(device, KeyEvent.ACTION_DOWN, code)
    private fun release(device: Int, code: Int) = key(device, KeyEvent.ACTION_UP, code)

    private fun motion(device: Int, values: Map<Int, Float>) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { values.forEach { (axis, value) -> setAxisValue(axis, value) } })
        input.onMotion(MotionEvent.obtain(0, time, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0,
            1f, 1f, device, 0, InputDevice.SOURCE_JOYSTICK, 0))
    }

    @Test fun defaultsFollowPositions() {
        val pad = defaultRetroProfile(RetroInputKind.GAMEPAD)
        assertEquals(RetroPadButton.B, pad.button(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RetroPadButton.A, pad.button(KeyEvent.KEYCODE_BUTTON_B))
        val remote = defaultRetroProfile(RetroInputKind.REMOTE)
        assertEquals(RetroPadButton.A, remote.button(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(remote.holdOpensMenu(KeyEvent.KEYCODE_BACK))
        assertEquals(RetroPadButton.B, defaultRetroProfile(RetroInputKind.KEYBOARD).button(KeyEvent.KEYCODE_Z))
    }

    @Test fun assigningMovesTheKeyAndTheDefaultNeedsNoStorage() {
        val keyboard = defaultRetroProfile(RetroInputKind.KEYBOARD)
        val changed = keyboard.assign(RetroInputTarget.Button(RetroPadButton.A), KeyEvent.KEYCODE_Z)
        assertEquals(RetroPadButton.A, changed.button(KeyEvent.KEYCODE_Z))
        assertTrue(changed.keys(RetroInputTarget.Button(RetroPadButton.B)).isEmpty())
        val stored = RetroInputConfig().withProfile(RetroInputKind.KEYBOARD, changed)
        assertTrue(RetroInputKind.KEYBOARD in stored.profiles)
        assertTrue(stored.withProfile(RetroInputKind.KEYBOARD, defaultRetroProfile(RetroInputKind.KEYBOARD)).profiles.isEmpty())
    }

    @Test fun configRoundTrips() {
        val remote = defaultRetroProfile(RetroInputKind.REMOTE)
            .assign(RetroInputTarget.Menu(RetroMenuTrigger.PRESS), KeyEvent.KEYCODE_GUIDE)
        val value = RetroInputConfig(startSelectMenu = false, dpadAsStick = true)
            .withProfile(RetroInputKind.REMOTE, remote)
            .withPort("pad", 2).withPort("tv-remote", RetroPorts.OFF)
        assertEquals(value, RetroInputConfig.decode(value.encode()))
        assertEquals(RetroInputConfig(), RetroInputConfig.decode(mapOf("port.x" to "9", "keys.nope.a" to "1")))
    }

    @Test fun controllersGetPlayersInOrderKeyboardAndRemoteJoinPlayerOne() {
        assertTrue(press(2, KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(press(1, KeyEvent.KEYCODE_BUTTON_B))
        assertTrue(press(3, KeyEvent.KEYCODE_X))
        assertEquals(RetroPadButton.B.mask or RetroPadButton.A.mask, input.buttons(0))
        assertEquals(RetroPadButton.A.mask, input.buttons(1))
        release(2, KeyEvent.KEYCODE_BUTTON_A)
        release(3, KeyEvent.KEYCODE_X)
        assertEquals(0, input.buttons(0))
    }

    @Test fun aPlayerSetInSettingsWinsAndOffLetsTheKeyThrough() {
        config = config.withPort("dev-1", 3).withPort("dev-4", RetroPorts.OFF)
        press(1, KeyEvent.KEYCODE_BUTTON_START)
        assertEquals(RetroPadButton.START.mask, input.buttons(3))
        assertFalse(press(4, KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun unmappedKeysTakeTheirNormalWay() {
        assertFalse(press(4, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(press(3, KeyEvent.KEYCODE_F12))
    }

    @Test fun remoteBackIsBWhenTappedAndTheMenuWhenHeld() {
        press(4, KeyEvent.KEYCODE_BACK)
        assertEquals(0, input.buttons(0))
        advance(100)
        release(4, KeyEvent.KEYCODE_BACK)
        assertEquals(RetroPadButton.B.mask, input.buttons(0))
        advance(RetroPadInput.PULSE_MS)
        assertEquals(0, input.buttons(0))
        assertEquals(0, menus)

        press(4, KeyEvent.KEYCODE_BACK)
        advance(700)
        assertEquals(1, menus)
        assertTrue(release(4, KeyEvent.KEYCODE_BACK))
        advance(500)
        assertEquals(0, input.buttons(0))
    }

    @Test fun hotkeysRewindWhileHeldAndToggleFastForward() {
        press(4, KeyEvent.KEYCODE_MEDIA_REWIND)
        press(3, KeyEvent.KEYCODE_DEL)
        release(4, KeyEvent.KEYCODE_MEDIA_REWIND)
        assertEquals(listOf(true), rewinds)
        release(3, KeyEvent.KEYCODE_DEL)
        assertEquals(listOf(true, false), rewinds)
        assertEquals(0, input.buttons(0))

        assertTrue(press(4, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        release(4, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        press(3, KeyEvent.KEYCODE_SPACE)
        assertEquals(2, fastForwards)

        // Opening the menu lets go of rewind.
        press(3, KeyEvent.KEYCODE_DEL)
        press(3, KeyEvent.KEYCODE_ESCAPE)
        assertEquals(listOf(true, false, true, false), rewinds)
        // Channel keys stay L and R on the remote.
        press(4, KeyEvent.KEYCODE_CHANNEL_UP)
        assertEquals(RetroPadButton.R.mask, input.buttons(0))
    }

    @Test fun menuKeysAndStartSelectOpenTheMenuAndReleaseEverything() {
        press(3, KeyEvent.KEYCODE_Z)
        press(4, KeyEvent.KEYCODE_MENU)
        assertEquals(1, menus)
        assertEquals(0, input.buttons(0))
        release(4, KeyEvent.KEYCODE_MENU)

        press(1, KeyEvent.KEYCODE_BUTTON_START)
        press(1, KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(2, menus)
        assertEquals(0, input.buttons(0))
        assertTrue(release(1, KeyEvent.KEYCODE_BUTTON_START))
        assertTrue(release(1, KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(0, input.buttons(0))

        config = config.copy(startSelectMenu = false)
        press(1, KeyEvent.KEYCODE_BUTTON_START)
        press(1, KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(2, menus)
        assertEquals(RetroPadButton.START.mask or RetroPadButton.SELECT.mask, input.buttons(0))
    }

    @Test fun sticksHatAndTriggersReachTheCore() {
        motion(1, mapOf(MotionEvent.AXIS_X to -1f, MotionEvent.AXIS_RZ to 0.5f,
            MotionEvent.AXIS_HAT_Y to 1f, MotionEvent.AXIS_RTRIGGER to 1f))
        assertEquals(-RetroPadInput.ANALOG_MAX, input.analog(0, 0, 0))
        assertEquals(RetroPadInput.ANALOG_MAX / 2 + 1, input.analog(0, 1, 1))
        assertEquals(RetroPadButton.DOWN.mask or RetroPadButton.R2.mask, input.buttons(0))
        motion(1, mapOf(MotionEvent.AXIS_X to 0.05f))
        assertEquals(0, input.analog(0, 0, 0))
        assertEquals(0, input.buttons(0))
    }

    @Test fun theDpadCanMoveTheLeftStick() {
        press(4, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, input.analog(0, 0, 0))
        release(4, KeyEvent.KEYCODE_DPAD_LEFT)
        config = config.copy(dpadAsStick = true)
        press(4, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(-RetroPadInput.ANALOG_MAX, input.analog(0, 0, 0))
        assertEquals(RetroPadButton.LEFT.mask, input.buttons(0))
    }

    @Test fun genericControllersReportTheRightStickOnRxRy() {
        assertEquals(GamepadAxisLayout.STANDARD, GamepadAxisLayout.forRanges(hasZ = true, zMin = -1f, rzMin = -1f, hasRx = true))
        val generic = GamepadAxisLayout.forRanges(hasZ = true, zMin = 0f, rzMin = 0f, hasRx = true)
        assertEquals(MotionEvent.AXIS_RX, generic.rightX)
        assertTrue(MotionEvent.AXIS_Z in generic.leftTrigger)
        assertTrue(MotionEvent.AXIS_RZ in generic.rightTrigger)
        assertEquals(GamepadAxisLayout.STANDARD, GamepadAxisLayout.forRanges(hasZ = false, zMin = null, rzMin = null, hasRx = false))
    }
}
