package com.ambient.tv.ui.gamepad.retro

import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.keyboard.KEY_BINDING_LONG_PRESS_MS
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.abs
import kotlin.math.roundToInt

/** An input device as the game layer sees it. [descriptor] is stable across reconnects. */
data class RetroInputDevice(val id: Int, val kind: RetroInputKind, val descriptor: String, val name: String)

/**
 * Controllers, keyboards and remotes as libretro RetroPads (players 1–4). Installed as
 * GamepadInput.rawSink while a game is in front: mapped keys and controller motion never reach the
 * app, everything else (volume, unmapped keys, devices set to off) takes its normal way.
 *
 * Events arrive on the main thread; [buttons] and [analog] are read by the core's thread.
 */
class RetroPadInput(
    private val config: () -> RetroInputConfig = { RetroInputSettings.config },
    private val device: (deviceId: Int) -> RetroInputDevice = ::retroInputDevice,
    private val schedule: (delayMs: Long, task: () -> Unit) -> (() -> Unit),
    private val axisLayout: (deviceId: Int) -> com.ambient.tv.ui.gamepad.GamepadAxisLayout =
        { com.ambient.tv.ui.gamepad.GamepadAxisLayout.of(InputDevice.getDevice(it)) },
    /** Opens the game menu (the player OSD). */
    private val onMenu: () -> Unit,
    /** Rewind hotkey held (true) or let go (false). */
    private val onRewind: (Boolean) -> Unit = {},
    /** Fast forward hotkey pressed: toggles. */
    private val onFastForward: () -> Unit = {},
) {
    private class DeviceState(val info: RetroInputDevice, val layout: com.ambient.tv.ui.gamepad.GamepadAxisLayout) {
        val keys = mutableMapOf<Int, RetroPadButton>()
        /** Held menu-hold keys that have not decided yet: key code → cancel. */
        val holds = mutableMapOf<Int, () -> Unit>()
        /** Keys whose press opened the menu; their release is swallowed. */
        val menuKeys = mutableSetOf<Int>()
        /** Held rewind hotkeys. */
        val rewindKeys = mutableSetOf<Int>()
        var axisButtons = 0
        var pulseButtons = 0
        val analog = IntArray(4)

        fun buttons(): Int = keys.values.fold(axisButtons or pulseButtons) { mask, button -> mask or button.mask }
    }

    private val devices = mutableMapOf<Int, DeviceState>()
    /** Controllers in the order they were first used: automatic players 1, 2, 3, 4. */
    private val controllerOrder = mutableListOf<String>()
    private val portButtons = AtomicIntegerArray(RetroPorts.COUNT)
    private val portAnalog = AtomicIntegerArray(RetroPorts.COUNT * 4)

    /** Called on the main thread after any player's buttons or sticks changed. */
    var onChanged: (() -> Unit)? = null

    /** RETRO_DEVICE_JOYPAD bitmask of [port] (bit = RETRO_DEVICE_ID_JOYPAD_*). Any thread. */
    fun buttons(port: Int): Int = if (port in 0 until RetroPorts.COUNT) portButtons.get(port) else 0

    /** RETRO_DEVICE_ANALOG: [index] 0 left / 1 right stick, [axis] 0 x / 1 y, −32767…32767. Any thread. */
    fun analog(port: Int, index: Int, axis: Int): Int =
        if (port in 0 until RetroPorts.COUNT && index in 0..1 && axis in 0..1) portAnalog.get(port * 4 + index * 2 + axis) else 0

    fun onEvent(event: InputEvent): Boolean = when (event) {
        is KeyEvent -> onKey(event)
        is MotionEvent -> onMotion(event)
        else -> false
    }

    /** The player [info] plays as right now, or null (off or more than four players). */
    fun port(info: RetroInputDevice): Int? {
        config().ports[info.descriptor]?.let { return it.takeIf { port -> port >= 0 } }
        if (info.kind != RetroInputKind.GAMEPAD) return 0
        if (info.descriptor !in controllerOrder) controllerOrder += info.descriptor
        return controllerOrder.indexOf(info.descriptor).takeIf { it < RetroPorts.COUNT }
    }

    fun onKey(event: KeyEvent): Boolean {
        val state = state(event.deviceId) ?: return false
        val profile = config().profile(state.info.kind)
        val code = event.keyCode
        if (!profile.handles(code) && code !in state.menuKeys) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) keyDown(state, profile, code)
            KeyEvent.ACTION_UP -> keyUp(state, profile, code, event.isCanceled)
        }
        return true
    }

    private fun keyDown(state: DeviceState, profile: RetroInputProfile, code: Int) {
        if (profile.rewinds(code)) {
            if (state.rewindKeys.add(code) && rewinding() == 1) onRewind(true)
            return
        }
        if (profile.fastForwards(code)) {
            onFastForward()
            return
        }
        if (profile.opensMenu(code)) {
            state.menuKeys += code
            openMenu()
            return
        }
        if (profile.holdOpensMenu(code)) {
            state.holds[code] = schedule(KEY_BINDING_LONG_PRESS_MS) {
                if (state.holds.remove(code) == null) return@schedule
                state.menuKeys += code
                openMenu()
            }
            return
        }
        val button = profile.button(code) ?: return
        state.keys[code] = button
        update(state)
        val held = state.buttons()
        val combo = RetroPadButton.START.mask or RetroPadButton.SELECT.mask
        if (state.info.kind == RetroInputKind.GAMEPAD && config().startSelectMenu && held and combo == combo) {
            // Both releases belong to the menu shortcut, not to the game.
            state.menuKeys += state.keys.filterValues { it == RetroPadButton.START || it == RetroPadButton.SELECT }.keys
            openMenu()
        }
    }

    private fun keyUp(state: DeviceState, profile: RetroInputProfile, code: Int, canceled: Boolean) {
        if (state.menuKeys.remove(code)) return
        if (state.rewindKeys.remove(code)) {
            if (rewinding() == 0) onRewind(false)
            return
        }
        state.holds.remove(code)?.let { cancel ->
            cancel()
            // A short press of a menu-hold key is its button, long enough for the core to see it.
            val button = profile.button(code)
            if (button != null && !canceled) pulse(state, button)
            return
        }
        if (state.keys.remove(code) != null) update(state)
    }

    private fun pulse(state: DeviceState, button: RetroPadButton) {
        state.pulseButtons = state.pulseButtons or button.mask
        update(state)
        schedule(PULSE_MS) {
            state.pulseButtons = state.pulseButtons and button.mask.inv()
            update(state)
        }
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val state = state(event.deviceId) ?: return false
        var buttons = 0
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX <= -0.5f) buttons = buttons or RetroPadButton.LEFT.mask
        if (hatX >= 0.5f) buttons = buttons or RetroPadButton.RIGHT.mask
        if (hatY <= -0.5f) buttons = buttons or RetroPadButton.UP.mask
        if (hatY >= 0.5f) buttons = buttons or RetroPadButton.DOWN.mask
        if (state.layout.leftTriggerValue(event) >= 0.5f) {
            buttons = buttons or RetroPadButton.L2.mask
        }
        if (state.layout.rightTriggerValue(event) >= 0.5f) {
            buttons = buttons or RetroPadButton.R2.mask
        }
        state.axisButtons = buttons
        state.analog[0] = stick(event.getAxisValue(MotionEvent.AXIS_X))
        state.analog[1] = stick(event.getAxisValue(MotionEvent.AXIS_Y))
        state.analog[2] = stick(state.layout.rightStickX(event))
        state.analog[3] = stick(state.layout.rightStickY(event))
        update(state)
        return true
    }

    private fun rewinding(): Int = devices.values.sumOf { it.rewindKeys.size }

    /** Devices playing as player [port] right now (for vibration). */
    fun deviceIds(port: Int): List<Int> = devices.filter { (_, state) -> port(state.info) == port }.keys.toList()

    /** Lets go of everything (game paused, menu opened, focus lost). */
    fun releaseAll() {
        if (rewinding() > 0) onRewind(false)
        devices.values.forEach { state -> state.holds.values.forEach { it() } }
        devices.clear()
        for (port in 0 until RetroPorts.COUNT) recompute(port)
    }

    fun deviceRemoved(deviceId: Int) {
        val state = devices.remove(deviceId) ?: return
        state.holds.values.forEach { it() }
        if (state.rewindKeys.isNotEmpty() && rewinding() == 0) onRewind(false)
        recomputeAll()
    }

    private fun openMenu() {
        if (rewinding() > 0) onRewind(false)
        // The game must not keep running with buttons held while its menu is open.
        devices.values.forEach { state ->
            state.rewindKeys.clear()
            state.keys.clear()
            state.axisButtons = 0
            state.pulseButtons = 0
            state.analog.fill(0)
        }
        recomputeAll()
        onMenu()
    }

    private fun state(deviceId: Int): DeviceState? {
        devices[deviceId]?.let { existing ->
            return existing.takeIf { port(it.info) != null }
        }
        val info = device(deviceId)
        if (port(info) == null) return null
        return DeviceState(info, axisLayout(deviceId)).also { devices[deviceId] = it }
    }

    private fun update(state: DeviceState) {
        val port = port(state.info) ?: return
        recompute(port)
    }

    private fun recomputeAll() {
        for (port in 0 until RetroPorts.COUNT) recompute(port)
    }

    private fun recompute(port: Int) {
        val playing = devices.values.filter { port(it.info) == port }
        val buttons = playing.fold(0) { mask, state -> mask or state.buttons() }
        portButtons.set(port, buttons)
        for (axis in 0 until 4) {
            // The strongest lean wins when several devices share a player.
            var value = playing.map { it.analog[axis] }.maxByOrNull { abs(it) } ?: 0
            if (axis < 2 && value == 0 && config().dpadAsStick) value = dpadAxis(buttons, axis)
            portAnalog.set(port * 4 + axis, value)
        }
        onChanged?.invoke()
    }

    private fun dpadAxis(buttons: Int, axis: Int): Int {
        val negative = if (axis == 0) RetroPadButton.LEFT else RetroPadButton.UP
        val positive = if (axis == 0) RetroPadButton.RIGHT else RetroPadButton.DOWN
        return when {
            buttons and negative.mask != 0 && buttons and positive.mask == 0 -> -ANALOG_MAX
            buttons and positive.mask != 0 && buttons and negative.mask == 0 -> ANALOG_MAX
            else -> 0
        }
    }

    private fun stick(value: Float): Int =
        if (abs(value) < STICK_DEADZONE) 0 else (value.coerceIn(-1f, 1f) * ANALOG_MAX).roundToInt()

    internal companion object {
        const val ANALOG_MAX = 0x7fff
        const val PULSE_MS = 80L
        const val STICK_DEADZONE = 0.1f
    }
}

/** Real devices someone can play with: controllers, keyboards and remotes with arrow keys. */
fun playableInputDevices(): List<RetroInputDevice> = InputDevice.getDeviceIds().toList()
    .mapNotNull { id -> InputDevice.getDevice(id)?.takeIf { !it.isVirtual }?.let { id to it } }
    .map { (id, device) -> retroInputDevice(id) to device }
    .filter { (info, device) ->
        info.kind != RetroInputKind.REMOTE ||
            (device.supportsSource(InputDevice.SOURCE_KEYBOARD) && device.hasKeys(KeyEvent.KEYCODE_DPAD_UP).firstOrNull() == true)
    }
    .map { it.first }
    .distinctBy { it.descriptor }

/** Classifies a real input device; unknown or virtual sources (phone remote) count as remote. */
fun retroInputDevice(deviceId: Int): RetroInputDevice {
    val device = InputDevice.getDevice(deviceId)
        ?: return RetroInputDevice(deviceId, RetroInputKind.REMOTE, "virtual", "")
    val kind = when {
        device.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
            (device.supportsSource(InputDevice.SOURCE_GAMEPAD) && device.hasKeys(KeyEvent.KEYCODE_BUTTON_A).firstOrNull() == true) ->
            RetroInputKind.GAMEPAD
        device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC -> RetroInputKind.KEYBOARD
        else -> RetroInputKind.REMOTE
    }
    return RetroInputDevice(deviceId, kind, device.descriptor ?: "device-$deviceId", device.name.orEmpty())
}
