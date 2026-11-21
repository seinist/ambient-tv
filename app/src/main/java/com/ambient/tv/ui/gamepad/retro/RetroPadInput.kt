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
    private val profileContext: RetroInputProfileContext? = null,
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
    /** Keep the device identity through a menu/pause release so hotplug still frees its slot. */
    private val observedDevices = mutableMapOf<Int, RetroInputDevice>()
    /** Controllers in the order they were first used: automatic players 1, 2, 3, 4. */
    /** Keep assigned player ports stable when another controller is unplugged. */
    private val automaticPorts = mutableMapOf<String, Int>()
    private val portButtons = AtomicIntegerArray(RetroPorts.COUNT)
    private val portAnalog = AtomicIntegerArray(RetroPorts.COUNT * 4)
    /** Virtual handset controls share player 1 with physical inputs; union, never replace, their state. */
    private var touchButtons = 0
    private val touchAnalog = IntArray(4)
    /** Authenticated companion phones have independent, explicitly assigned player ports. */
    private val remoteReserved = BooleanArray(RetroPorts.COUNT)
    private val remoteButtons = IntArray(RetroPorts.COUNT)
    private val remoteAnalog = IntArray(RetroPorts.COUNT * 4)
    /** Each paired handset keeps its own persistent device descriptor and inherited profile. */
    private val remoteDescriptors = arrayOfNulls<String>(RetroPorts.COUNT)
    /** Profiles are immutable until the settings reference changes: no rebuild on every 60Hz frame. */
    private val remoteProfileSettings = arrayOfNulls<RetroInputConfig>(RetroPorts.COUNT)
    private val remoteProfiles = arrayOfNulls<RetroInputProfile>(RetroPorts.COUNT)
    private val remoteRawButtons = IntArray(RetroPorts.COUNT)
    /** Buttons held while an OSD opens must be physically released before playing again. */
    private val remoteSuppressed = IntArray(RetroPorts.COUNT)
    private val remoteRewindHeld = BooleanArray(RetroPorts.COUNT)
    private val remoteMenuCombo = BooleanArray(RetroPorts.COUNT)
    private val remotePulseButtons = IntArray(RetroPorts.COUNT)
    private val remoteHoldTimers = mutableMapOf<Pair<Int, RetroPadButton>, () -> Unit>()
    private val remoteHoldOpened = mutableSetOf<Pair<Int, RetroPadButton>>()

    /** Called on the main thread after any player's buttons or sticks changed. */
    var onChanged: (() -> Unit)? = null

    /** RETRO_DEVICE_JOYPAD bitmask of [port] (bit = RETRO_DEVICE_ID_JOYPAD_*). Any thread. */
    fun buttons(port: Int): Int = if (port in 0 until RetroPorts.COUNT) portButtons.get(port) else 0

    /** RETRO_DEVICE_ANALOG: [index] 0 left / 1 right stick, [axis] 0 x / 1 y, −32767…32767. Any thread. */
    fun analog(port: Int, index: Int, axis: Int): Int =
        if (port in 0 until RetroPorts.COUNT && index in 0..1 && axis in 0..1) portAnalog.get(port * 4 + index * 2 + axis) else 0

    /** A held virtual button. Always pair down/up; menu, pause and dispose clear it as well. */
    fun touchButton(button: RetroPadButton, pressed: Boolean) {
        val newMask = if (pressed) touchButtons or button.mask else touchButtons and button.mask.inv()
        if (newMask == touchButtons) return
        touchButtons = newMask
        recompute(0)
    }

    /** Virtual stick axes (−1…1). Coexists with a physical stick on the same port. */
    fun touchStick(index: Int, x: Float, y: Float) {
        if (index !in 0..1) return
        val at = index * 2
        val nextX = stick(x.coerceIn(-1f, 1f))
        val nextY = stick(y.coerceIn(-1f, 1f))
        if (touchAnalog[at] == nextX && touchAnalog[at + 1] == nextY) return
        touchAnalog[at] = nextX
        touchAnalog[at + 1] = nextY
        recompute(0)
    }

    /** Player ports in use by local physical devices; remote claims avoid these where possible. */
    fun localUsedPorts(): Set<Int> = devices.values.mapNotNull { port(it.info) }.toSet()

    /** Reserve a phone's port so automatically assigned physical controllers skip it. */
    fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String? = null) {
        if (port !in 0 until RetroPorts.COUNT) return
        if (!reserved) clearRemotePort(port)
        remoteReserved[port] = reserved
        remoteDescriptors[port] = if (reserved) descriptor else null
        remoteProfileSettings[port] = null
        remoteProfiles[port] = null
        // A physical auto-assignment may have moved when this port was reserved/freed.
        recomputeAll()
    }

    /** A disconnect/pause is a hard release, not a short tap: never generate a mapped pulse. */
    fun clearRemotePort(port: Int, suppressHeld: Boolean = false) {
        if (port !in 0 until RetroPorts.COUNT || !remoteReserved[port]) return
        if (suppressHeld) remoteSuppressed[port] = remoteSuppressed[port] or remoteRawButtons[port]
        else remoteSuppressed[port] = 0
        remoteHoldTimers.keys.filter { it.first == port }.toList().forEach {
            remoteHoldTimers.remove(it)?.invoke()
        }
        remoteHoldOpened.removeAll { it.first == port }
        remoteRawButtons[port] = 0
        remotePulseButtons[port] = 0
        remoteMenuCombo[port] = false
        val wasRewinding = remoteRewindHeld[port]
        remoteRewindHeld[port] = false
        remoteButtons[port] = 0
        for (axis in 0 until 4) remoteAnalog[port * 4 + axis] = 0
        if (wasRewinding && rewinding() == 0 && remoteRewindHeld.none { it }) onRewind(false)
        recompute(port)
    }

    private fun companionProfile(port: Int): RetroInputProfile? {
        val descriptor = remoteDescriptors[port] ?: return null
        val settings = config()
        if (remoteProfiles[port] == null || remoteProfileSettings[port] !== settings) {
            remoteProfiles[port] = profileContext?.let { context ->
                settings.profile(RetroInputKind.COMPANION, context, deviceDescriptor = descriptor)
            } ?: settings.deviceProfile(RetroInputKind.COMPANION, descriptor)
            remoteProfileSettings[port] = settings
        }
        return remoteProfiles[port]
    }

    /** Complete phone state, rather than synthetic clicks: holds, diagonals and analog axes survive. */
    fun remoteState(port: Int, buttons: Int, leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        if (port !in 0 until RetroPorts.COUNT || !remoteReserved[port]) return
        val at = port * 4
        val incomingButtons = buttons and 0xffff
        // A held phone menu button is not another press on every 60Hz heartbeat.
        remoteSuppressed[port] = remoteSuppressed[port] and incomingButtons
        val sourceButtons = incomingButtons and remoteSuppressed[port].inv()
        val profile = companionProfile(port)
        val previousRaw = remoteRawButtons[port]
        if (profile != null && previousRaw != sourceButtons) {
            for (source in RetroPadButton.entries) {
                val code = companionControllerKey(source)
                val wasDown = previousRaw and source.mask != 0
                val isDown = sourceButtons and source.mask != 0
                if (isDown == wasDown) continue
                val holdKey = port to source
                if (isDown) {
                    if (profile.opensMenu(code)) {
                        remoteRawButtons[port] = sourceButtons
                        openMenu()
                        return
                    }
                    if (profile.fastForwards(code)) onFastForward()
                    if (profile.holdOpensMenu(code)) {
                        remoteHoldTimers[holdKey]?.invoke()
                        remoteHoldTimers[holdKey] = schedule(KEY_BINDING_LONG_PRESS_MS) {
                            if (remoteHoldTimers.remove(holdKey) != null) {
                                remoteHoldOpened += holdKey
                                openMenu()
                            }
                        }
                    }
                } else if (profile.holdOpensMenu(code)) {
                    val cancel = remoteHoldTimers.remove(holdKey)
                    cancel?.invoke()
                    if (remoteHoldOpened.remove(holdKey).not() && cancel != null) {
                        profile.button(code)?.let { target ->
                            remotePulseButtons[port] = remotePulseButtons[port] or target.mask
                            schedule(PULSE_MS) {
                                remotePulseButtons[port] = remotePulseButtons[port] and target.mask.inv()
                                recompute(port)
                            }
                        }
                    }
                }
            }
        }
        remoteRawButtons[port] = sourceButtons
        val rewind = profile != null && RetroPadButton.entries.any {
            sourceButtons and it.mask != 0 && profile.rewinds(companionControllerKey(it))
        }
        if (rewind != remoteRewindHeld[port]) {
            remoteRewindHeld[port] = rewind
            onRewind(rewinding() > 0 || remoteRewindHeld.any { it })
        }
        var nextButtons = if (profile != null) companionMappedButtons(sourceButtons, profile)
            else sourceButtons
        if (profile != null) RetroPadButton.entries.forEach { source ->
            if (profile.holdOpensMenu(companionControllerKey(source)) && sourceButtons and source.mask != 0)
                profile.button(companionControllerKey(source))?.let { nextButtons = nextButtons and it.mask.inv() }
        }
        if (profile != null && config().startSelectMenu) {
            val combo = RetroPadButton.START.mask or RetroPadButton.SELECT.mask
            val together = nextButtons and combo == combo
            if (together && !remoteMenuCombo[port]) {
                remoteMenuCombo[port] = true
                openMenu()
                return
            }
            if (!together) remoteMenuCombo[port] = false
        }
        nextButtons = nextButtons or remotePulseButtons[port]
        val nextX = stick(leftX)
        val nextY = stick(leftY)
        val nextRX = stick(rightX)
        val nextRY = stick(rightY)
        // Heartbeat frames are often identical: don't force four-port recompute and native
        // onChanged dispatch every 500 ms when the actual controller state has not moved.
        if (remoteButtons[port] == nextButtons && remoteAnalog[at] == nextX &&
            remoteAnalog[at + 1] == nextY && remoteAnalog[at + 2] == nextRX &&
            remoteAnalog[at + 3] == nextRY) return
        remoteButtons[port] = nextButtons
        remoteAnalog[at] = nextX
        remoteAnalog[at + 1] = nextY
        remoteAnalog[at + 2] = nextRX
        remoteAnalog[at + 3] = nextRY
        recompute(port)
    }

    fun onEvent(event: InputEvent): Boolean = when (event) {
        is KeyEvent -> onKey(event)
        is MotionEvent -> onMotion(event)
        else -> false
    }

    /** The player [info] plays as right now, or null (off or more than four players). */
    fun port(info: RetroInputDevice): Int? {
        config().ports[info.descriptor]?.let { return it.takeIf { port -> port >= 0 } }
        if (info.kind != RetroInputKind.GAMEPAD) return 0
        val reserved = config().ports.values.filter { it in 0 until RetroPorts.COUNT }.toSet() +
            (0 until RetroPorts.COUNT).filter { remoteReserved[it] }
        val existing = automaticPorts[info.descriptor]
        if (existing != null && existing !in reserved) return existing
        val occupied = automaticPorts.filterKeys { it != info.descriptor }.values.toSet() + reserved
        val free = (0 until RetroPorts.COUNT).firstOrNull { it !in occupied }
        if (free == null) automaticPorts.remove(info.descriptor)
        else automaticPorts[info.descriptor] = free
        return free
    }

    fun onKey(event: KeyEvent): Boolean {
        val state = state(event.deviceId) ?: return false
        val profile = profileContext?.let {
            config().profile(state.info.kind, it, deviceDescriptor = state.info.descriptor)
        } ?: config().deviceProfile(state.info.kind, state.info.descriptor)
        val code = event.keyCode
        val emergencyBack = state.info.kind == RetroInputKind.REMOTE &&
            code == KeyEvent.KEYCODE_BACK && !profile.opensMenu(code) && !profile.holdOpensMenu(code)
        if (!profile.handles(code) && code !in state.menuKeys && !emergencyBack) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                if (emergencyBack) emergencyBackDown(state, code) else keyDown(state, profile, code)
            }
            KeyEvent.ACTION_UP -> keyUp(state, profile, code, event.isCanceled)
        }
        return true
    }

    private fun emergencyBackDown(state: DeviceState, code: Int) {
        if (state.holds.containsKey(code)) return
        state.holds[code] = schedule(EMERGENCY_MENU_HOLD_MS) {
            if (state.holds.remove(code) == null) return@schedule
            state.menuKeys += code
            openMenu()
        }
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
        val profile = profileContext?.let {
            config().profile(state.info.kind, it, deviceDescriptor = state.info.descriptor)
        } ?: config().deviceProfile(state.info.kind, state.info.descriptor)
        fun mapped(code: Int): Int = profile.button(code)?.mask ?: 0
        var buttons = 0
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX <= -0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_LEFT)
        if (hatX >= 0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (hatY <= -0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_UP)
        if (hatY >= 0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_DOWN)
        if (state.layout.leftTriggerValue(event) >= 0.5f) {
            buttons = buttons or mapped(KeyEvent.KEYCODE_BUTTON_L2)
        }
        if (state.layout.rightTriggerValue(event) >= 0.5f) {
            buttons = buttons or mapped(KeyEvent.KEYCODE_BUTTON_R2)
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
        touchButtons = 0
        touchAnalog.fill(0)
        for (port in 0 until RetroPorts.COUNT) if (remoteReserved[port]) clearRemotePort(port, suppressHeld = true)
        remoteButtons.fill(0)
        remoteAnalog.fill(0)
        for (port in 0 until RetroPorts.COUNT) recompute(port)
    }

    fun deviceRemoved(deviceId: Int) {
        val state = devices.remove(deviceId)
        val info = observedDevices.remove(deviceId) ?: state?.info
        if (info?.kind == RetroInputKind.GAMEPAD) automaticPorts.remove(info.descriptor)
        state?.holds?.values?.forEach { it() }
        if (state?.rewindKeys?.isNotEmpty() == true && rewinding() == 0) onRewind(false)
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
        touchButtons = 0
        touchAnalog.fill(0)
        for (port in 0 until RetroPorts.COUNT) {
            if (remoteReserved[port]) clearRemotePort(port, suppressHeld = true)
        }
        remoteButtons.fill(0)
        remoteAnalog.fill(0)
        recomputeAll()
        onMenu()
    }

    private fun state(deviceId: Int): DeviceState? {
        devices[deviceId]?.let { existing ->
            return existing.takeIf { port(it.info) != null }
        }
        val info = device(deviceId)
        if (port(info) == null) return null
        observedDevices[deviceId] = info
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
        val buttons = playing.fold(remoteButtons[port] or (if (port == 0) touchButtons else 0)) { mask, state -> mask or state.buttons() }
        portButtons.set(port, buttons)
        for (axis in 0 until 4) {
            // The strongest lean wins when several devices share a player.
            var value = (playing.map { it.analog[axis] } + listOf(remoteAnalog[port * 4 + axis]) +
                (if (port == 0) listOf(touchAnalog[axis]) else emptyList())).maxByOrNull { abs(it) } ?: 0
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
        const val EMERGENCY_MENU_HOLD_MS = 1_800L
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
