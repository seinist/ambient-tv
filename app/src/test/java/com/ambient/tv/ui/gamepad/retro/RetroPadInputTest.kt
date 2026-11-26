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

    @Test fun virtualSticksPreserveFineTravelAfterTheTouchDeadzone() {
        input.touchStick(0, .04f, -.03f)
        assertTrue(input.analog(0, 0, 0) > 0)
        assertTrue(input.analog(0, 0, 1) < 0)
        input.reserveRemotePort(1, true)
        input.remoteState(1, 0, .04f, -.03f, .02f, -.01f)
        assertEquals(input.analog(0, 0, 0), input.analog(1, 0, 0))
        assertTrue(input.analog(1, 1, 0) > 0)
        input.touchStick(0, Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(0, input.analog(0, 0, 0))
        assertEquals(0, input.analog(0, 0, 1))
    }

    @Test fun autoHideRequiresActiveMappedGamepadRatherThanTouchOrNoise() {
        input.touchButton(RetroPadButton.A, true)
        assertFalse(input.hasActiveGamepad(1))
        motion(1, mapOf(MotionEvent.AXIS_X to .03f))
        assertFalse(input.hasActiveGamepad(1))
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(input.hasActiveGamepad(1))
        config = config.withPort("dev-1", RetroPorts.OFF)
        assertFalse(input.hasActiveGamepad(1))
        press(3, KeyEvent.KEYCODE_Z)
        assertFalse(input.hasActiveGamepad(3))
    }

    @Test fun rumbleTargetsOnlyPhysicalGamepadsOnTheRequestedPort() {
        config = config.withPort("dev-1", 0).withPort("dev-2", 1)
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        press(2, KeyEvent.KEYCODE_BUTTON_A)
        press(3, KeyEvent.KEYCODE_Z)
        press(4, KeyEvent.KEYCODE_DPAD_CENTER)
        input.touchButton(RetroPadButton.A, true)
        input.reserveRemotePort(2, true)
        input.remoteState(2, RetroPadButton.A.mask, 0f, 0f, 0f, 0f)

        assertEquals(listOf(1), input.rumbleDeviceIds(0))
        assertEquals(listOf(2), input.rumbleDeviceIds(1))
        assertTrue(input.rumbleDeviceIds(2).isEmpty())

        input.releaseAll()
        assertEquals(listOf(1), input.rumbleDeviceIds(0))
        config = config.withPort("dev-1", RetroPorts.OFF)
        assertTrue(input.rumbleDeviceIds(0).isEmpty())
        input.deviceRemoved(2)
        assertTrue(input.rumbleDeviceIds(1).isEmpty())
    }

    @Test fun rumbleRoutesReuseSnapshotsAndFollowReservationHotplugAndConfig() {
        assertTrue(input.rumbleDeviceIds(0).isEmpty())
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        press(2, KeyEvent.KEYCODE_BUTTON_A)
        val first = input.rumbleDeviceIds(0)
        assertEquals(listOf(1), first)
        assertTrue(first === input.rumbleDeviceIds(0))
        assertEquals(listOf(2), input.rumbleDeviceIds(1))
        input.releaseAll()
        assertTrue(first === input.rumbleDeviceIds(0))

        input.reserveRemotePort(0, true)
        assertTrue(input.rumbleDeviceIds(0).isEmpty())
        assertEquals(listOf(2), input.rumbleDeviceIds(1))
        assertEquals(listOf(1), input.rumbleDeviceIds(2))
        input.reserveRemotePort(0, false)
        assertEquals(listOf(1), input.rumbleDeviceIds(2))
        config = config.withPort("dev-1", 1).withPort("dev-2", RetroPorts.OFF)
        // A normal input path may notice the changed config before rumble does.
        press(1, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(listOf(1), input.rumbleDeviceIds(1))
        assertTrue(input.rumbleDeviceIds(2).isEmpty())
        input.deviceRemoved(1)
        assertTrue(input.rumbleDeviceIds(1).isEmpty())
        assertEquals(listOf(1), first) // published snapshots are never mutated
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(1), input.rumbleDeviceIds(1))
    }

    @Test fun aggregatePublicationKeepsAllChannelsAndOnlyNotifiesChanges() {
        val ports = mutableListOf<Int>()
        input.onPortChanged = { ports += it }
        input.reserveRemotePort(1, true)
        input.remoteState(1, RetroPadButton.A.mask, .5f, -.5f, .25f, -.25f, .4f, .8f)
        assertEquals(listOf(1), ports)
        assertEquals(RetroPadButton.A.mask, input.buttons(1))
        assertEquals(16384, input.analog(1, 0, 0))
        assertEquals(-16383, input.analog(1, 0, 1))
        assertEquals(8192, input.analog(1, 1, 0))
        assertEquals(-8192, input.analog(1, 1, 1))
        assertEquals(13107, input.trigger(1, 0))
        assertEquals(26214, input.trigger(1, 1))
        repeat(100) {
            input.remoteState(1, RetroPadButton.A.mask, .5f, -.5f, .25f, -.25f, .4f, .8f)
        }
        assertEquals(listOf(1), ports)
        input.clearRemotePort(1)
        assertEquals(listOf(1, 1), ports)
        assertEquals(0, input.buttons(1))
        for (index in 0..1) {
            assertEquals(0, input.analog(1, index, 0))
            assertEquals(0, input.analog(1, index, 1))
            assertEquals(0, input.trigger(1, index))
        }
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

    @Test fun hardwareTriggerPressureDoesNotBecomeFullFromItsDuplicateKeyEvent() {
        motion(1, mapOf(MotionEvent.AXIS_LTRIGGER to .25f, MotionEvent.AXIS_RTRIGGER to .75f))
        assertEquals(8192, input.trigger(0, 0))
        assertEquals(24575, input.trigger(0, 1))
        assertEquals(RetroPadButton.R2.mask, input.buttons(0))
        press(1, KeyEvent.KEYCODE_BUTTON_L2)
        press(1, KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(8192, input.trigger(0, 0))
        assertEquals(24575, input.trigger(0, 1))
        motion(1, emptyMap())
        assertEquals(0, input.trigger(0, 0))
        assertEquals(0, input.trigger(0, 1))
        assertEquals(0, input.buttons(0))
        input.deviceRemoved(1)
        // A digital-only pad remains fully usable without ever reporting trigger axes.
        press(1, KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(32767, input.trigger(0, 0))
    }

    @Test fun triggerAxesRespectUserRemappingAndUnchangedMotionDoesNotDispatch() {
        config = config.assignDevice(RetroInputKind.GAMEPAD, "dev-1",
            RetroInputTarget.Button(RetroPadButton.R2), KeyEvent.KEYCODE_BUTTON_L2)
        var changed = 0
        input.onChanged = { changed++ }
        repeat(10) { motion(1, mapOf(MotionEvent.AXIS_LTRIGGER to .4f)) }
        assertEquals(0, input.trigger(0, 0))
        assertEquals(13107, input.trigger(0, 1))
        assertEquals(1, changed)
        assertTrue(input.hasActiveGamepad(1))
        input.releaseAll()
        assertEquals(0, input.trigger(0, 1))
    }

    @Test fun portNotificationsAreLocalAndOnlyForChanges() {
        val ports = mutableListOf<Int>()
        input.onPortChanged = { ports += it }
        input.touchButton(RetroPadButton.A, true)
        input.touchButton(RetroPadButton.A, true)
        input.touchButton(RetroPadButton.A, false)
        assertEquals(listOf(0, 0), ports)
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
        val value = RetroInputConfig(menuCombo = RetroMenuCombo.OFF, dpadAsStick = true)
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

        config = config.copy(menuCombo = RetroMenuCombo.OFF)
        press(1, KeyEvent.KEYCODE_BUTTON_START)
        press(1, KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(2, menus)
        assertEquals(RetroPadButton.START.mask or RetroPadButton.SELECT.mask, input.buttons(0))
    }

    @Test fun menuComboSettingsMigrateAndRoundTripEveryChoice() {
        assertEquals(RetroMenuCombo.START_SELECT, RetroInputConfig.decode(emptyMap()).menuCombo)
        assertEquals(RetroMenuCombo.START_SELECT, RetroInputConfig.decode(mapOf("start_select_menu" to "1")).menuCombo)
        assertEquals(RetroMenuCombo.OFF, RetroInputConfig.decode(mapOf("start_select_menu" to "0")).menuCombo)
        assertEquals(RetroMenuCombo.OFF, RetroInputConfig.decode(
            mapOf("menu_combo" to "future_unknown", "start_select_menu" to "0")).menuCombo)
        for (combo in RetroMenuCombo.entries) {
            val chosen = RetroInputConfig(menuCombo = combo)
            assertEquals(chosen, RetroInputConfig.decode(chosen.encode()))
            assertEquals(if (combo == RetroMenuCombo.START_SELECT) "1" else "0",
                chosen.encode()["start_select_menu"])
        }
    }

    @Test fun selectableMenuCombosNeverDelaySingleButtonsOrPublishTheCompletedChord() {
        val choices = mapOf(
            RetroMenuCombo.START_SELECT to listOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT),
            RetroMenuCombo.L3_R3 to listOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR),
            RetroMenuCombo.L1_R1 to listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1),
            RetroMenuCombo.L1_R1_START to listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_START),
        )
        val snapshots = mutableListOf<Int>()
        input.onChanged = { snapshots += input.buttons(0) }
        for ((combo, keys) in choices) {
            config = config.copy(menuCombo = combo)
            val previous = menus
            snapshots.clear()
            press(1, keys.first())
            assertTrue(input.buttons(0) != 0)
            assertEquals(previous, menus)
            for (code in keys.drop(1)) press(1, code)
            assertEquals(previous + 1, menus)
            assertTrue(snapshots.none { combo.matches(it) })
            assertEquals(0, input.buttons(0))
            for (code in keys) assertTrue(release(1, code))
            assertEquals(0, input.buttons(0))
            input.releaseAll()
        }
    }

    @Test fun menuCombosDoNotCombineDevicesAndOffKeepsDirectGuideMenu() {
        config = config.copy(menuCombo = RetroMenuCombo.L3_R3)
        press(1, KeyEvent.KEYCODE_BUTTON_THUMBL)
        press(2, KeyEvent.KEYCODE_BUTTON_THUMBR)
        assertEquals(0, menus)
        release(1, KeyEvent.KEYCODE_BUTTON_THUMBL)
        release(2, KeyEvent.KEYCODE_BUTTON_THUMBR)
        // Start + Select is ordinary gameplay when another shortcut is selected.
        press(1, KeyEvent.KEYCODE_BUTTON_START)
        press(1, KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(0, menus)
        release(1, KeyEvent.KEYCODE_BUTTON_START)
        release(1, KeyEvent.KEYCODE_BUTTON_SELECT)
        config = config.copy(menuCombo = RetroMenuCombo.OFF)
        press(1, KeyEvent.KEYCODE_BUTTON_THUMBL)
        press(1, KeyEvent.KEYCODE_BUTTON_THUMBR)
        assertEquals(0, menus)
        assertEquals(RetroPadButton.L3.mask or RetroPadButton.R3.mask, input.buttons(0))
        press(1, KeyEvent.KEYCODE_BUTTON_MODE)
        assertEquals(1, menus)
        assertEquals(0, input.buttons(0))
    }

    @Test fun pairedControllerMenuComboSuppressesHeldKeysUntilRelease() {
        config = config.copy(menuCombo = RetroMenuCombo.L3_R3)
        input.reserveRemotePort(1, true, companionControllerDescriptor("menu-combo"))
        val combo = RetroMenuCombo.L3_R3.mask
        fun send(mask: Int) = input.remoteState(1, mask, 0f, 0f, 0f, 0f)
        send(RetroPadButton.L3.mask)
        assertEquals(RetroPadButton.L3.mask, input.buttons(1))
        send(combo)
        assertEquals(1, menus)
        assertEquals(0, input.buttons(1))
        repeat(5) { send(combo) }
        assertEquals(1, menus)
        send(0)
        send(combo)
        assertEquals(2, menus)
        assertEquals(0, input.buttons(1))
        send(0)
        config = config.copy(menuCombo = RetroMenuCombo.OFF)
        send(combo)
        assertEquals(2, menus)
        assertEquals(combo, input.buttons(1))
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

    @Test fun touchAndHardwareButtonsCanBeHeldTogether() {
        press(1, KeyEvent.KEYCODE_BUTTON_A) // Xbox A is RetroPad B.
        input.touchButton(RetroPadButton.A, true)
        assertEquals(RetroPadButton.A.mask or RetroPadButton.B.mask, input.buttons(0))
        input.touchButton(RetroPadButton.A, false)
        assertEquals(RetroPadButton.B.mask, input.buttons(0))
        release(1, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, input.buttons(0))
    }

    @Test fun virtualSticksSharePortWithPhysicalControllers() {
        input.touchStick(0, 0.75f, -0.6f)
        assertTrue(input.analog(0, 0, 0) > 20_000)
        assertTrue(input.analog(0, 0, 1) < -15_000)
        // The physical stick can be independently held; the larger deflection wins.
        motion(1, mapOf(MotionEvent.AXIS_X to -0.9f))
        assertTrue(input.analog(0, 0, 0) < -25_000)
        input.touchStick(0, 0f, 0f)
        assertTrue(input.analog(0, 0, 0) < -25_000)
        input.releaseAll()
        assertEquals(0, input.analog(0, 0, 0))
        assertEquals(0, input.analog(0, 0, 1))
    }

    @Test fun remotePhonesKeepTheirOwnPortsAndClearOnRelease() {
        input.reserveRemotePort(0, true)
        input.reserveRemotePort(2, true)
        input.remoteState(0, RetroPadButton.A.mask, 0.5f, 0f, 0f, 0f)
        input.remoteState(2, RetroPadButton.B.mask, 0f, 0f, -1f, 0f)
        assertEquals(RetroPadButton.A.mask, input.buttons(0))
        assertEquals(RetroPadButton.B.mask, input.buttons(2))
        assertTrue(input.analog(0, 0, 0) > 10_000)
        assertEquals(-RetroPadInput.ANALOG_MAX, input.analog(2, 1, 0))
        input.reserveRemotePort(0, false)
        assertEquals(0, input.buttons(0))
        assertEquals(0, input.analog(0, 0, 0))
        assertEquals(RetroPadButton.B.mask, input.buttons(2))
        input.releaseAll()
        assertEquals(0, input.buttons(2))
        assertEquals(0, input.analog(2, 1, 0))
    }

    @Test fun localControllerAvoidsAReservedPhonePort() {
        input.reserveRemotePort(0, true)
        assertTrue(press(1, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RetroPadButton.B.mask, input.buttons(1))
        assertEquals(0, input.buttons(0))
    }

    @Test fun companionPhoneControllerMapsItsButtonsUsingTheExistingDeviceProfile() {
        val descriptor = companionControllerDescriptor("phone-one")
        config = config.assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Button(RetroPadButton.A), companionControllerKey(RetroPadButton.B))
        input.reserveRemotePort(2, true, descriptor)
        input.remoteState(2, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(RetroPadButton.A.mask, input.buttons(2))
        assertEquals(0, input.buttons(0))
        // A changed setting applies to the next state, even when a button stays pressed.
        config = config.resetDevice(RetroInputKind.COMPANION, descriptor)
        input.remoteState(2, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(RetroPadButton.B.mask, input.buttons(2))
        input.reserveRemotePort(2, false)
        assertEquals(0, input.buttons(2))
    }

    @Test fun companionProjectionKeepsPressureLiveDuringUnchangedButtonAndStickPackets() {
        val descriptor = companionControllerDescriptor("pressure-cache")
        config = config.assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Button(RetroPadButton.R2), companionControllerKey(RetroPadButton.L2))
        input.reserveRemotePort(1, true, descriptor)
        input.reserveRemotePort(2, true)
        val changed = mutableListOf<Int>()
        input.onPortChanged = { changed += it }
        fun send(left: Float, x: Float = 0f) =
            input.remoteState(1, RetroPadButton.B.mask, x, 0f, 0f, 0f, left, 0f)
        send(.25f)
        assertEquals(0, input.trigger(1, 0))
        assertEquals(8192, input.trigger(1, 1))
        repeat(10) { send(.25f) }
        assertEquals(listOf(1), changed)
        send(.75f, .5f)
        assertEquals(24575, input.trigger(1, 1))
        assertEquals(16384, input.analog(1, 0, 0))
        input.remoteState(2, 0, 0f, 0f, 0f, 0f, .5f, 0f)
        assertEquals(16384, input.trigger(2, 0))
        assertEquals(24575, input.trigger(1, 1))
        config = config.resetDevice(RetroInputKind.COMPANION, descriptor)
        send(.75f, .5f)
        assertEquals(24575, input.trigger(1, 0))
        assertEquals(0, input.trigger(1, 1))
        input.clearRemotePort(1, suppressHeld = true)
        send(.75f)
        assertEquals(0, input.trigger(1, 0))
        assertEquals(0, input.buttons(1))
        send(0f)
        send(.25f)
        assertEquals(8192, input.trigger(1, 0))
        input.reserveRemotePort(1, false)
        input.reserveRemotePort(1, true)
        send(.75f)
        assertEquals(24575, input.trigger(1, 0))
    }

    @Test fun companionProjectionMatchesPreviousMappingForAllButtonMasks() {
        val code = ::companionControllerKey
        val profile = RetroInputProfile(
            buttons = mapOf(
                RetroPadButton.L2 to listOf(code(RetroPadButton.R2), code(RetroPadButton.A)),
                RetroPadButton.R2 to listOf(code(RetroPadButton.L2), code(RetroPadButton.B)),
                RetroPadButton.X to listOf(code(RetroPadButton.X), code(RetroPadButton.Y)),
            ),
            menu = mapOf(
                RetroMenuTrigger.REWIND to listOf(code(RetroPadButton.X)),
                RetroMenuTrigger.HOLD to listOf(code(RetroPadButton.B)),
                RetroMenuTrigger.PRESS to listOf(code(RetroPadButton.L)),
                RetroMenuTrigger.FAST_FORWARD to listOf(code(RetroPadButton.R)),
            ),
        )
        val projection = CompanionInputProjection()
        fun hotkey(key: Int) = profile.opensMenu(key) || profile.holdOpensMenu(key) ||
            profile.rewinds(key) || profile.fastForwards(key)
        for (mask in 0..0xffff) {
            var expected = companionMappedButtons(mask, profile)
            var rewind = false
            var left = 0
            var right = 0
            for (source in RetroPadButton.entries) {
                val key = code(source)
                val target = profile.button(key)
                val down = mask and source.mask != 0
                if (down && profile.rewinds(key)) rewind = true
                if (hotkey(key)) {
                    if (down && target != null) expected = expected and target.mask.inv()
                    continue
                }
                val value = when (source) {
                    RetroPadButton.L2 -> 8192
                    RetroPadButton.R2 -> 24575
                    else -> if (down) RetroPadInput.ANALOG_MAX else 0
                }
                if (target == RetroPadButton.L2) left = maxOf(left, value)
                if (target == RetroPadButton.R2) right = maxOf(right, value)
            }
            projection.update(profile, mask)
            assertEquals(expected, projection.buttons)
            assertEquals(rewind, projection.rewind)
            assertEquals(left, projection.leftPressure(8192, 24575))
            assertEquals(right, projection.rightPressure(8192, 24575))
            projection.update(profile, mask) // cache hit must be identical
            assertEquals(expected, projection.buttons)
        }
        projection.update(null, RetroPadButton.L2.mask)
        assertEquals(RetroPadButton.L2.mask, projection.buttons)
        assertFalse(projection.rewind)
        assertEquals(8192, projection.leftPressure(8192, 24575))
        assertEquals(24575, projection.rightPressure(8192, 24575))
    }

    @Test fun companionProfileHasAnIdentityLayoutAndSurvivesSettingsEncoding() {
        val descriptor = companionControllerDescriptor("phone-two")
        val default = defaultRetroProfile(RetroInputKind.COMPANION)
        assertEquals(RetroPadButton.UP.mask or RetroPadButton.B.mask,
            companionMappedButtons(RetroPadButton.UP.mask or RetroPadButton.B.mask, default))
        val saved = RetroInputConfig()
            .withPort(descriptor, 1)
            .assignDevice(RetroInputKind.COMPANION, descriptor,
                RetroInputTarget.Button(RetroPadButton.X), companionControllerKey(RetroPadButton.B))
        val restored = RetroInputConfig.decode(saved.encode())
        assertEquals(saved, restored)
        assertEquals(1, restored.ports[descriptor])
        assertEquals(RetroPadButton.X.mask,
            companionMappedButtons(RetroPadButton.B.mask, restored.deviceProfile(RetroInputKind.COMPANION, descriptor)))
    }

    @Test fun phoneCoreOverrideIsAppliedOnlyForItsSelectedLibretroCore() {
        val context = RetroInputProfileContext("snes", "snes9x", "game-one")
        val descriptor = companionControllerDescriptor("core-specific-phone")
        val saved = RetroInputConfig().assign(RetroInputKind.COMPANION, context,
            RetroInputLayerScope.CORE, RetroInputTarget.Button(RetroPadButton.X),
            companionControllerKey(RetroPadButton.B))
        fun player(ctx: RetroInputProfileContext) = RetroPadInput(
            config = { saved }, profileContext = ctx,
            schedule = { _, _ -> {} }, onMenu = {},
        ).apply { reserveRemotePort(1, true, descriptor) }
        val first = player(context)
        val second = player(context.copy(coreId = "bsnes"))
        first.remoteState(1, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        second.remoteState(1, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(RetroPadButton.X.mask, first.buttons(1))
        assertEquals(RetroPadButton.B.mask, second.buttons(1))
    }

    @Test fun configuredPhoneMenuAndRewindBindingsActuallyReachTheGamePlayer() {
        val descriptor = companionControllerDescriptor("phone-hotkeys")
        config = config.assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Menu(RetroMenuTrigger.REWIND), companionControllerKey(RetroPadButton.L))
        config = config.assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Menu(RetroMenuTrigger.PRESS), companionControllerKey(RetroPadButton.Y))
        input.reserveRemotePort(1, true, descriptor)
        input.remoteState(1, RetroPadButton.L.mask, 0f, 0f, 0f, 0f)
        assertEquals(listOf(true), rewinds)
        assertEquals(0, input.buttons(1))
        input.remoteState(1, 0, 0f, 0f, 0f, 0f)
        assertEquals(listOf(true, false), rewinds)
        input.remoteState(1, RetroPadButton.Y.mask, 0f, 0f, 0f, 0f)
        assertEquals(1, menus)
        assertEquals(0, input.buttons(1))
        input.reserveRemotePort(1, false)
    }

    @Test fun pausedPhoneReleasesItsHoldWithoutAFramePulse() {
        val descriptor = companionControllerDescriptor("phone-hold")
        config = config.assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Menu(RetroMenuTrigger.HOLD), companionControllerKey(RetroPadButton.B))
        input.reserveRemotePort(0, true, descriptor)
        input.remoteState(0, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(0, input.buttons(0))
        input.clearRemotePort(0)
        advance(2_000L)
        assertEquals(0, menus)
        assertEquals(0, input.buttons(0))
    }

    @Test fun heldPhoneMenuKeyDoesNotReopenOnEveryHeartbeat() {
        val descriptor = companionControllerDescriptor("phone-menu-repeat")
        config = config.assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Menu(RetroMenuTrigger.PRESS), companionControllerKey(RetroPadButton.Y))
        input.reserveRemotePort(1, true, descriptor)
        input.remoteState(1, RetroPadButton.Y.mask, 0f, 0f, 0f, 0f)
        assertEquals(1, menus)
        repeat(5) { input.remoteState(1, RetroPadButton.Y.mask, 0f, 0f, 0f, 0f) }
        assertEquals(1, menus)
        input.remoteState(1, 0, 0f, 0f, 0f, 0f)
        input.remoteState(1, RetroPadButton.Y.mask, 0f, 0f, 0f, 0f)
        assertEquals(2, menus)
    }

    @Test fun pausedPhoneCannotReplayAStillHeldButton() {
        val descriptor = companionControllerDescriptor("phone-pause-hold")
        input.reserveRemotePort(0, true, descriptor)
        input.remoteState(0, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(RetroPadButton.B.mask, input.buttons(0))
        input.releaseAll()
        assertEquals(0, input.buttons(0))
        input.remoteState(0, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(0, input.buttons(0))
        input.remoteState(0, 0, 0f, 0f, 0f, 0f)
        input.remoteState(0, RetroPadButton.B.mask, 0f, 0f, 0f, 0f)
        assertEquals(RetroPadButton.B.mask, input.buttons(0))
    }

    @Test fun phoneSpecialLayoutSettingsSurviveTheExistingBackupRoundTrip() {
        val key = companionTouchProfileKey("tv-fingerprint", "nds")
        val value = CompanionTouchProfile(mode = "hybrid", input = "touch", region = "lower", motion = true)
        val config = RetroInputConfig().withCompanionTouchProfile(key, value)
        assertEquals(value, RetroInputConfig.decode(config.encode()).companionTouchProfiles[key])
        assertEquals(config, RetroInputConfig.decode(config.encode()))
        val malformed = config.encode().toMutableMap()
        malformed["companionui.bad"] = "gun,unknown,nonsense,3"
        assertEquals(config, RetroInputConfig.decode(malformed))
    }

    @Test fun pauseAndMenuReleaseVirtualButtonsToo() {
        input.touchButton(RetroPadButton.START, true)
        input.touchStick(1, 1f, 0f)
        input.releaseAll()
        assertEquals(0, input.buttons(0))
        assertEquals(0, input.analog(0, 1, 0))
        input.touchButton(RetroPadButton.A, true)
        press(4, KeyEvent.KEYCODE_MENU)
        assertEquals(0, input.buttons(0))
    }
    @Test fun systemCoreGameInputProfilesInheritOnlyChangedButtons() {
        val context = RetroInputProfileContext("snes", "snes9x", "rom-one")
        val otherGame = context.copy(gameId = "rom-two")
        var current = RetroInputConfig()
        // Use a face button that was previously free; the other default stays intact.
        current = current.assign(RetroInputKind.GAMEPAD, context, RetroInputLayerScope.SYSTEM,
            RetroInputTarget.Button(RetroPadButton.B), KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(RetroPadButton.B, current.profile(RetroInputKind.GAMEPAD, context).button(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(RetroPadButton.A, current.profile(RetroInputKind.GAMEPAD, context).button(KeyEvent.KEYCODE_BUTTON_B))

        current = current.assign(RetroInputKind.GAMEPAD, context, RetroInputLayerScope.CORE,
            RetroInputTarget.Button(RetroPadButton.X), KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(RetroPadButton.X, current.profile(RetroInputKind.GAMEPAD, context).button(KeyEvent.KEYCODE_BUTTON_A))
        // Another core on the same system has the system profile, but not this core's changes.
        // The system reassigned B away from physical BUTTON_A; another core inherits that absence.
        assertEquals(null, current.profile(RetroInputKind.GAMEPAD, context.copy(coreId = "bsnes"))
            .button(KeyEvent.KEYCODE_BUTTON_A))

        current = current.assign(RetroInputKind.GAMEPAD, context, RetroInputLayerScope.GAME,
            RetroInputTarget.Button(RetroPadButton.SELECT), KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(RetroPadButton.SELECT, current.profile(RetroInputKind.GAMEPAD, context)
            .button(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(RetroPadButton.R, current.profile(RetroInputKind.GAMEPAD, otherGame)
            .button(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(current, RetroInputConfig.decode(current.encode()))
        assertEquals(
            current.profile(RetroInputKind.GAMEPAD, context),
            RetroInputConfig.decode(current.encode()).profile(RetroInputKind.GAMEPAD, context),
        )
        val reverted = current.resetScope(RetroInputKind.GAMEPAD, context, RetroInputLayerScope.GAME)
        assertEquals(RetroPadButton.R, reverted.profile(RetroInputKind.GAMEPAD, context)
            .button(KeyEvent.KEYCODE_BUTTON_R1))
    }

    @Test fun emptyScopedProfileDoesNotPolluteStorage() {
        val context = RetroInputProfileContext("megadrive", "genesis_plus_gx", "rom/with space.zip")
        val baseline = RetroInputConfig()
        val defaultKey = KeyEvent.KEYCODE_BUTTON_A
        val unchanged = baseline.assign(RetroInputKind.GAMEPAD, context, RetroInputLayerScope.CORE,
            RetroInputTarget.Button(RetroPadButton.B), defaultKey)
        assertTrue(unchanged.scopedProfiles.isEmpty())
        assertTrue(unchanged.encode().keys.none { it.startsWith("preset.") })
    }


    @Test fun sharedDolphinCoreIsSeparatedByConsoleAndOverridesWin() {
        val wii = RetroInputProfileContext("wii", "dolphin", "wii-game")
        val cube = RetroInputProfileContext("gamecube", "dolphin", "cube-game")
        val target = RetroInputTarget.Button(RetroPadButton.B)
        var state = RetroInputConfig().assign(
            RetroInputKind.GAMEPAD, wii, RetroInputLayerScope.CORE,
            target, KeyEvent.KEYCODE_BUTTON_Y,
        )
        assertEquals(RetroPadButton.B, state.profile(RetroInputKind.GAMEPAD, wii)
            .button(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(RetroPadButton.X, state.profile(RetroInputKind.GAMEPAD, cube)
            .button(KeyEvent.KEYCODE_BUTTON_Y))
        // A parent-level update cannot steal the key deliberately chosen in the child.
        state = state.assign(RetroInputKind.GAMEPAD, wii, RetroInputLayerScope.SYSTEM,
            RetroInputTarget.Button(RetroPadButton.A), KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(RetroPadButton.B, state.profile(RetroInputKind.GAMEPAD, wii)
            .button(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(RetroPadButton.A, state.profile(RetroInputKind.GAMEPAD, wii,
            RetroInputLayerScope.SYSTEM).button(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(state, RetroInputConfig.decode(state.encode()))
    }

    @Test fun unplugReleasesOnlyItsOwnInputWithoutMovingOtherPlayers() {
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        press(2, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(RetroPadButton.B.mask, input.buttons(0))
        assertEquals(RetroPadButton.A.mask, input.buttons(1))
        input.deviceRemoved(1)
        assertEquals(0, input.buttons(0))
        assertEquals(RetroPadButton.A.mask, input.buttons(1))
        // The same descriptor after reconnect can reclaim its previous free port.
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(RetroPadButton.B.mask, input.buttons(0))
        assertEquals(RetroPadButton.A.mask, input.buttons(1))
    }

    @Test fun remappingRemoteBackCannotRemoveTheEmergencyMenu() {
        config = config.withProfile(RetroInputKind.REMOTE,
            defaultRetroProfile(RetroInputKind.REMOTE)
                .assign(RetroInputTarget.Button(RetroPadButton.A), KeyEvent.KEYCODE_BACK))
        press(4, KeyEvent.KEYCODE_BACK)
        advance(RetroPadInput.EMERGENCY_MENU_HOLD_MS + 50)
        assertEquals(1, menus)
        release(4, KeyEvent.KEYCODE_BACK)
        assertEquals(0, input.buttons(0))
    }

    @Test fun physicalGamepadProfileIsInheritedThenOverriddenBySystemCoreAndGame() {
        val ctx = RetroInputProfileContext("snes", "snes9x", "supermario")
        val key = KeyEvent.KEYCODE_BUTTON_Y
        var c = RetroInputConfig()
            .assignDevice(RetroInputKind.GAMEPAD, "dual-sense", RetroInputTarget.Button(RetroPadButton.A), key)
        assertEquals(RetroPadButton.A,
            c.profile(RetroInputKind.GAMEPAD, ctx, deviceDescriptor = "dual-sense").button(key))
        assertEquals(RetroPadButton.X,
            c.profile(RetroInputKind.GAMEPAD, ctx, deviceDescriptor = "xbox").button(key))
        c = c.assign(RetroInputKind.GAMEPAD, ctx, RetroInputLayerScope.SYSTEM,
            RetroInputTarget.Button(RetroPadButton.B), key)
        assertEquals(RetroPadButton.B,
            c.profile(RetroInputKind.GAMEPAD, ctx, deviceDescriptor = "dual-sense").button(key))
        assertEquals(c, RetroInputConfig.decode(c.encode()))
        assertEquals(c.profile(RetroInputKind.GAMEPAD, ctx, deviceDescriptor = "dual-sense"),
            RetroInputConfig.decode(c.encode()).profile(RetroInputKind.GAMEPAD, ctx, deviceDescriptor = "dual-sense"))
        assertTrue(c.resetDevice(RetroInputKind.GAMEPAD, "dual-sense").deviceProfiles.isEmpty())
    }

    @Test fun disconnectAfterOpeningOsdStillReleasesAutomaticPort() {
        press(1, KeyEvent.KEYCODE_BUTTON_A)
        press(2, KeyEvent.KEYCODE_BUTTON_B)
        input.releaseAll() // the game menu cleared active button states
        input.deviceRemoved(1) // Android now forgets the disconnected device
        val fresh = RetroInputDevice(5, RetroInputKind.GAMEPAD, "dev-5", "Replacement")
        assertEquals(0, input.port(fresh))
        assertEquals(1, input.port(RetroInputDevice(2, RetroInputKind.GAMEPAD, "dev-2", "Remaining")))
    }

}
