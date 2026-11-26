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
 * Events arrive on the main thread; [buttons], [analog] and [trigger] are safe to read elsewhere.
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
        var digitalAxisButtons = 0
        var pulseButtons = 0
        val analog = IntArray(4)
        val triggers = IntArray(2)
        val triggerAxisSeen = BooleanArray(2)
        var profileSettings: RetroInputConfig? = null
        var profile: RetroInputProfile? = null

        fun digitalButtons(): Int {
            var result = digitalAxisButtons or pulseButtons
            for ((code, button) in keys) {
                // Android may emit a binary key alongside the same analog trigger. Keep the
                // axis' pressure and threshold authoritative once this device has reported it.
                if (code == KeyEvent.KEYCODE_BUTTON_L2 && triggerAxisSeen[0]) continue
                if (code == KeyEvent.KEYCODE_BUTTON_R2 && triggerAxisSeen[1]) continue
                result = result or button.mask
            }
            return result
        }

        fun buttons(): Int = axisButtons or digitalButtons()
    }

    private val devices = mutableMapOf<Int, DeviceState>()
    /** Keep the device identity through a menu/pause release so hotplug still frees its slot. */
    private val observedDevices = mutableMapOf<Int, RetroInputDevice>()
    /** Controllers in the order they were first used: automatic players 1, 2, 3, 4. */
    /** Keep assigned player ports stable when another controller is unplugged. */
    private val automaticPorts = mutableMapOf<String, Int>()
    private val portButtons = AtomicIntegerArray(RetroPorts.COUNT)
    private val portAnalog = AtomicIntegerArray(RetroPorts.COUNT * 4)
    private val portTriggers = AtomicIntegerArray(RetroPorts.COUNT * 2)
    private var portSettings: RetroInputConfig? = null
    private val resolvedPorts = mutableMapOf<String, Int>()
    // Main-thread snapshots: rebuilt only when routing or observed device identity changes.
    private val rumbleRoutes = arrayOfNulls<List<Int>>(RetroPorts.COUNT)
    /** Virtual handset controls share player 1 with physical inputs; union, never replace, their state. */
    private var touchButtons = 0
    private val touchAnalog = IntArray(4)
    /** Authenticated companion phones have independent, explicitly assigned player ports. */
    private val remoteReserved = BooleanArray(RetroPorts.COUNT)
    private val remoteButtons = IntArray(RetroPorts.COUNT)
    private val remoteAnalog = IntArray(RetroPorts.COUNT * 4)
    private val remoteTriggers = IntArray(RetroPorts.COUNT * 2)
    private val remoteRawTriggers = IntArray(RetroPorts.COUNT * 2)
    private val remoteSuppressedTriggers = BooleanArray(RetroPorts.COUNT * 2)
    /** Each paired handset keeps its own persistent device descriptor and inherited profile. */
    private val remoteDescriptors = arrayOfNulls<String>(RetroPorts.COUNT)
    /** Profiles are immutable until the settings reference changes: no rebuild on every 60Hz frame. */
    private val remoteProfileSettings = arrayOfNulls<RetroInputConfig>(RetroPorts.COUNT)
    private val remoteProfiles = arrayOfNulls<RetroInputProfile>(RetroPorts.COUNT)
    private val remoteProjections = Array(RetroPorts.COUNT) { CompanionInputProjection() }
    private val remoteRawButtons = IntArray(RetroPorts.COUNT)
    /** Buttons held while an OSD opens must be physically released before playing again. */
    private val remoteSuppressed = IntArray(RetroPorts.COUNT)
    private val remoteRewindHeld = BooleanArray(RetroPorts.COUNT)
    private val remotePulseButtons = IntArray(RetroPorts.COUNT)
    private val remoteHoldTimers = mutableMapOf<Pair<Int, RetroPadButton>, () -> Unit>()
    private val remoteHoldOpened = mutableSetOf<Pair<Int, RetroPadButton>>()

    /** Called on the main thread only when an aggregate button, stick or pressure changed. */
    var onChanged: (() -> Unit)? = null
    /** Immediate native hand-off, only for the changed aggregate port. Main thread. */
    var onPortChanged: ((Int) -> Unit)? = null

    /** RETRO_DEVICE_JOYPAD bitmask of [port] (bit = RETRO_DEVICE_ID_JOYPAD_*). Any thread. */
    fun buttons(port: Int): Int = if (port in 0 until RetroPorts.COUNT) portButtons.get(port) else 0

    /** RETRO_DEVICE_ANALOG: [index] 0 left / 1 right stick, [axis] 0 x / 1 y, −32767…32767. Any thread. */
    fun analog(port: Int, index: Int, axis: Int): Int =
        if (port in 0 until RetroPorts.COUNT && index in 0..1 && axis in 0..1) portAnalog.get(port * 4 + index * 2 + axis) else 0

    /** RETRO_DEVICE_INDEX_ANALOG_BUTTON L2/R2 pressure, 0…32767. Any thread. */
    fun trigger(port: Int, index: Int): Int =
        if (port in 0 until RetroPorts.COUNT && index in 0..1) portTriggers.get(port * 2 + index) else 0

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
        val nextX = virtualStick(x)
        val nextY = virtualStick(y)
        if (touchAnalog[at] == nextX && touchAnalog[at + 1] == nextY) return
        touchAnalog[at] = nextX
        touchAnalog[at + 1] = nextY
        recompute(0)
    }

    /** Player ports in use by local physical devices; remote claims avoid these where possible. */
    fun localUsedPorts(): Set<Int> = devices.values.mapNotNull { port(it.info) }.toSet()

    /** Main thread: active, enabled physical gameplay input, excluding touch/Companion holds. */
    fun hasActiveGamepad(deviceId: Int): Boolean = devices[deviceId]?.let {
        it.info.kind == RetroInputKind.GAMEPAD && port(it.info) != null &&
            (it.buttons() != 0 || it.analog.any { axis -> axis != 0 } || it.triggers.any { value -> value != 0 })
    } ?: false

    /** Reserve a phone's port so automatically assigned physical controllers skip it. */
    fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String? = null) {
        if (port !in 0 until RetroPorts.COUNT) return
        if (!reserved) clearRemotePort(port)
        remoteReserved[port] = reserved
        invalidatePorts()
        remoteDescriptors[port] = if (reserved) descriptor else null
        remoteProfileSettings[port] = null
        remoteProfiles[port] = null
        remoteProjections[port] = CompanionInputProjection()
        // A physical auto-assignment may have moved when this port was reserved/freed.
        recomputeAll()
    }

    /** A disconnect/pause is a hard release, not a short tap: never generate a mapped pulse. */
    fun clearRemotePort(port: Int, suppressHeld: Boolean = false) {
        if (port !in 0 until RetroPorts.COUNT || !remoteReserved[port]) return
        if (suppressHeld) remoteSuppressed[port] = remoteSuppressed[port] or remoteRawButtons[port]
        else remoteSuppressed[port] = 0
        for (index in 0..1) {
            val at = port * 2 + index
            remoteSuppressedTriggers[at] = suppressHeld &&
                (remoteSuppressedTriggers[at] || remoteRawTriggers[at] > 0)
            remoteRawTriggers[at] = 0
            remoteTriggers[at] = 0
        }
        remoteHoldTimers.keys.filter { it.first == port }.toList().forEach {
            remoteHoldTimers.remove(it)?.invoke()
        }
        remoteHoldOpened.removeAll { it.first == port }
        remoteRawButtons[port] = 0
        remotePulseButtons[port] = 0
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

    /** Labels follow this handset's remap, just as its actual buttons do. Metadata polls only. */
    fun remoteInputDescriptions(port: Int, core: Map<String, String>): Map<String, String> {
        if (port !in 0 until RetroPorts.COUNT) return emptyMap()
        val profile = companionProfile(port)
        return buildMap {
            for (source in RetroPadButton.entries) {
                val code = companionControllerKey(source)
                if (profile != null && (profile.opensMenu(code) || profile.holdOpensMenu(code) ||
                        profile.rewinds(code) || profile.fastForwards(code))) continue
                val target = if (profile == null) source else profile.button(code) ?: continue
                core["$port:${target.id}"]?.let { put("$port:${source.id}", it) }
            }
        }
    }

    /** Complete phone state, rather than synthetic clicks: holds, diagonals and analog axes survive. */
    fun remoteState(port: Int, buttons: Int, leftX: Float, leftY: Float, rightX: Float, rightY: Float,
                    leftTrigger: Float? = null, rightTrigger: Float? = null) {
        if (port !in 0 until RetroPorts.COUNT || !remoteReserved[port]) return
        val at = port * 4
        val incomingButtons = buttons and 0xffff
        val triggerAt = port * 2
        val incomingLeft = leftTrigger?.let(::pressure) ?: digitalPressure(incomingButtons, RetroPadButton.L2)
        val incomingRight = rightTrigger?.let(::pressure) ?: digitalPressure(incomingButtons, RetroPadButton.R2)
        remoteSuppressedTriggers[triggerAt] = remoteSuppressedTriggers[triggerAt] && incomingLeft > 0
        remoteSuppressedTriggers[triggerAt + 1] = remoteSuppressedTriggers[triggerAt + 1] && incomingRight > 0
        remoteRawTriggers[triggerAt] = incomingLeft
        remoteRawTriggers[triggerAt + 1] = incomingRight
        // A held phone menu button is not another press on every 60Hz heartbeat.
        remoteSuppressed[port] = remoteSuppressed[port] and incomingButtons
        val sourceButtons = incomingButtons and remoteSuppressed[port].inv()
        val profile = companionProfile(port)
        val previousRaw = remoteRawButtons[port]
        val previousPulse = remotePulseButtons[port]
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
        val projection = remoteProjections[port]
        projection.update(profile, sourceButtons)
        val rewind = projection.rewind
        if (rewind != remoteRewindHeld[port]) {
            remoteRewindHeld[port] = rewind
            onRewind(rewinding() > 0 || remoteRewindHeld.any { it })
        }
        val nextButtons = projection.buttons
        // A full chord must come from this one paired controller, not a heartbeat,
        // a previously suppressed key or buttons held on another player's device.
        if (profile != null && sourceButtons != previousRaw && config().menuCombo.matches(nextButtons)) {
            openMenu()
            return
        }
        val effectiveLeft = if (remoteSuppressedTriggers[triggerAt]) 0 else incomingLeft
        val effectiveRight = if (remoteSuppressedTriggers[triggerAt + 1]) 0 else incomingRight
        val nextLeft = projection.leftPressure(effectiveLeft, effectiveRight)
        val nextRight = projection.rightPressure(effectiveLeft, effectiveRight)
        val nextX = virtualStick(leftX)
        val nextY = virtualStick(leftY)
        val nextRX = virtualStick(rightX)
        val nextRY = virtualStick(rightY)
        // Heartbeat frames are often identical: don't force four-port recompute and native
        // onChanged dispatch every 500 ms when the actual controller state has not moved.
        if (remoteButtons[port] == nextButtons && remoteAnalog[at] == nextX &&
            remoteAnalog[at + 1] == nextY && remoteAnalog[at + 2] == nextRX &&
            remoteAnalog[at + 3] == nextRY && remoteTriggers[triggerAt] == nextLeft &&
            remoteTriggers[triggerAt + 1] == nextRight && remotePulseButtons[port] == previousPulse) return
        remoteButtons[port] = nextButtons
        remoteAnalog[at] = nextX
        remoteAnalog[at + 1] = nextY
        remoteAnalog[at + 2] = nextRX
        remoteAnalog[at + 3] = nextRY
        remoteTriggers[triggerAt] = nextLeft
        remoteTriggers[triggerAt + 1] = nextRight
        recompute(port)
    }

    fun onEvent(event: InputEvent): Boolean = when (event) {
        is KeyEvent -> onKey(event)
        is MotionEvent -> onMotion(event)
        else -> false
    }

    /** The player [info] plays as right now, or null (off or more than four players). */
    fun port(info: RetroInputDevice): Int? {
        val settings = currentPortSettings()
        resolvedPorts[info.descriptor]?.let { return it.takeIf { value -> value >= 0 } }
        val assigned = settings.ports[info.descriptor]
        val result = when {
            assigned != null && assigned != RetroPorts.AUTO -> assigned.takeIf { it in 0 until RetroPorts.COUNT }
            info.kind != RetroInputKind.GAMEPAD -> 0
            else -> {
                var reserved = 0
                for (value in settings.ports.values) if (value in 0 until RetroPorts.COUNT) reserved = reserved or (1 shl value)
                for (value in 0 until RetroPorts.COUNT) if (remoteReserved[value]) reserved = reserved or (1 shl value)
                val existing = automaticPorts[info.descriptor]
                if (existing != null && reserved and (1 shl existing) == 0) existing
                else {
                    var occupied = reserved
                    for ((descriptor, value) in automaticPorts) if (descriptor != info.descriptor)
                        occupied = occupied or (1 shl value)
                    val free = (0 until RetroPorts.COUNT).firstOrNull { occupied and (1 shl it) == 0 }
                    if (free == null) automaticPorts.remove(info.descriptor)
                    else automaticPorts[info.descriptor] = free
                    free
                }
            }
        }
        resolvedPorts[info.descriptor] = result ?: RetroPorts.OFF
        return result
    }

    private fun invalidatePorts() {
        resolvedPorts.clear()
        rumbleRoutes.fill(null)
    }

    private fun currentPortSettings(): RetroInputConfig {
        val settings = config()
        if (portSettings !== settings) {
            portSettings = settings
            invalidatePorts()
        }
        return settings
    }

    private fun profile(state: DeviceState): RetroInputProfile {
        val settings = config()
        if (state.profile == null || state.profileSettings !== settings) {
            state.profile = profileContext?.let {
                settings.profile(state.info.kind, it, deviceDescriptor = state.info.descriptor)
            } ?: settings.deviceProfile(state.info.kind, state.info.descriptor)
            state.profileSettings = settings
        }
        return requireNotNull(state.profile)
    }

    fun onKey(event: KeyEvent): Boolean {
        val state = state(event.deviceId) ?: return false
        val profile = profile(state)
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
        if (state.info.kind == RetroInputKind.GAMEPAD) {
            val combo = config().menuCombo
            // Only real simultaneous key holds on this device count. Never combine
            // players, stick axes or synthetic short-press pulses into a menu chord.
            if (button.mask and combo.mask != 0) {
                var held = 0
                for (pressed in state.keys.values) held = held or pressed.mask
                if (combo.matches(held)) {
                    // Consume the completing key before publishing a full chord to
                    // the core. Do not delay ordinary single-button gameplay.
                    state.menuKeys += state.keys.filterValues { it.mask and combo.mask != 0 }.keys
                    openMenu()
                    return
                }
            }
        }
        update(state)
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
        val profile = profile(state)
        fun mapped(code: Int): Int = profile.button(code)?.mask ?: 0
        var buttons = 0
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX <= -0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_LEFT)
        if (hatX >= 0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (hatY <= -0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_UP)
        if (hatY >= 0.5f) buttons = buttons or mapped(KeyEvent.KEYCODE_DPAD_DOWN)
        state.digitalAxisButtons = buttons
        val left = pressure(state.layout.leftTriggerValue(event))
        val right = pressure(state.layout.rightTriggerValue(event))
        if (left > 0) state.triggerAxisSeen[0] = true
        if (right > 0) state.triggerAxisSeen[1] = true
        state.triggers.fill(0)
        fun trigger(code: Int, value: Int) {
            val target = profile.button(code) ?: return
            if (profile.opensMenu(code) || profile.holdOpensMenu(code) || profile.rewinds(code) || profile.fastForwards(code)) return
            if (value >= (ANALOG_MAX + 1) / 2) buttons = buttons or target.mask
            when (target) {
                RetroPadButton.L2 -> state.triggers[0] = maxOf(state.triggers[0], value)
                RetroPadButton.R2 -> state.triggers[1] = maxOf(state.triggers[1], value)
                else -> Unit
            }
        }
        trigger(KeyEvent.KEYCODE_BUTTON_L2, left)
        trigger(KeyEvent.KEYCODE_BUTTON_R2, right)
        state.axisButtons = buttons
        state.analog[0] = stick(event.getAxisValue(MotionEvent.AXIS_X))
        state.analog[1] = stick(event.getAxisValue(MotionEvent.AXIS_Y))
        state.analog[2] = stick(state.layout.rightStickX(event))
        state.analog[3] = stick(state.layout.rightStickY(event))
        update(state)
        return true
    }

    private fun rewinding(): Int = devices.values.sumOf { it.rewindKeys.size }

    /** Physical gamepads assigned to [port], even after pause releases their held input. */
    fun rumbleDeviceIds(port: Int): List<Int> {
        if (port !in 0 until RetroPorts.COUNT) return emptyList()
        currentPortSettings()
        rumbleRoutes[port]?.let { return it }
        // Preserve observed-device order and the existing automatic assignment rules.
        return buildList {
            for (info in observedDevices.values) {
                if (info.kind == RetroInputKind.GAMEPAD && port(info) == port) add(info.id)
            }
        }.also { rumbleRoutes[port] = it }
    }

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
        remoteTriggers.fill(0)
        for (port in 0 until RetroPorts.COUNT) recompute(port)
    }

    fun deviceRemoved(deviceId: Int) {
        val state = devices.remove(deviceId)
        val info = observedDevices.remove(deviceId) ?: state?.info
        if (info?.kind == RetroInputKind.GAMEPAD) automaticPorts.remove(info.descriptor)
        invalidatePorts()
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
            state.digitalAxisButtons = 0
            state.pulseButtons = 0
            state.analog.fill(0)
            state.triggers.fill(0)
        }
        touchButtons = 0
        touchAnalog.fill(0)
        for (port in 0 until RetroPorts.COUNT) {
            if (remoteReserved[port]) clearRemotePort(port, suppressHeld = true)
        }
        remoteButtons.fill(0)
        remoteAnalog.fill(0)
        remoteTriggers.fill(0)
        recomputeAll()
        onMenu()
    }

    private fun state(deviceId: Int): DeviceState? {
        devices[deviceId]?.let { existing ->
            return existing.takeIf { port(it.info) != null }
        }
        val info = device(deviceId)
        if (port(info) == null) return null
        if (observedDevices.put(deviceId, info) != info) rumbleRoutes.fill(null)
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
        val virtualButtons = remoteButtons[port] or remotePulseButtons[port] or (if (port == 0) touchButtons else 0)
        var buttons = virtualButtons
        val at = port * 4
        val triggerAt = port * 2
        fun strongest(a: Int, b: Int): Int = if (abs(a) >= abs(b)) a else b
        var leftX = 0
        var leftY = 0
        var rightX = 0
        var rightY = 0
        val pulseAndTouch = remotePulseButtons[port] or (if (port == 0) touchButtons else 0)
        var leftTrigger = maxOf(remoteTriggers[triggerAt], digitalPressure(pulseAndTouch, RetroPadButton.L2))
        var rightTrigger = maxOf(remoteTriggers[triggerAt + 1], digitalPressure(pulseAndTouch, RetroPadButton.R2))
        // One pass, without allocating filtered device lists or six lists of candidate axes.
        for (state in devices.values) {
            if (port(state.info) != port) continue
            val digital = state.digitalButtons()
            buttons = buttons or state.axisButtons or digital
            leftX = strongest(leftX, state.analog[0])
            leftY = strongest(leftY, state.analog[1])
            rightX = strongest(rightX, state.analog[2])
            rightY = strongest(rightY, state.analog[3])
            leftTrigger = maxOf(leftTrigger, state.triggers[0], digitalPressure(digital, RetroPadButton.L2))
            rightTrigger = maxOf(rightTrigger, state.triggers[1], digitalPressure(digital, RetroPadButton.R2))
        }
        // Preserve physical-device priority for equal opposing travel, then remote, then touch.
        leftX = strongest(leftX, remoteAnalog[at])
        leftY = strongest(leftY, remoteAnalog[at + 1])
        rightX = strongest(rightX, remoteAnalog[at + 2])
        rightY = strongest(rightY, remoteAnalog[at + 3])
        if (port == 0) {
            leftX = strongest(leftX, touchAnalog[0])
            leftY = strongest(leftY, touchAnalog[1])
            rightX = strongest(rightX, touchAnalog[2])
            rightY = strongest(rightY, touchAnalog[3])
        }
        if (config().dpadAsStick) {
            if (leftX == 0) leftX = dpadAxis(buttons, 0)
            if (leftY == 0) leftY = dpadAxis(buttons, 1)
        }
        var changed = portButtons.publishIfChanged(port, buttons)
        if (portAnalog.publishIfChanged(at, leftX)) changed = true
        if (portAnalog.publishIfChanged(at + 1, leftY)) changed = true
        if (portAnalog.publishIfChanged(at + 2, rightX)) changed = true
        if (portAnalog.publishIfChanged(at + 3, rightY)) changed = true
        if (portTriggers.publishIfChanged(triggerAt, leftTrigger)) changed = true
        if (portTriggers.publishIfChanged(triggerAt + 1, rightTrigger)) changed = true
        if (changed) {
            onPortChanged?.invoke(port)
            onChanged?.invoke()
        }
    }

    // All writers are on the main thread. Readers still get volatile atomic values.
    // Avoid seven read-modify-write operations for unchanged high-rate input events.
    private fun AtomicIntegerArray.publishIfChanged(index: Int, value: Int): Boolean {
        if (get(index) == value) return false
        set(index, value)
        return true
    }

    private fun digitalPressure(buttons: Int, button: RetroPadButton): Int =
        if (buttons and button.mask != 0) ANALOG_MAX else 0

    private fun pressure(value: Float): Int =
        if (!value.isFinite()) 0 else (value.coerceIn(0f, 1f) * ANALOG_MAX).roundToInt()

    private fun dpadAxis(buttons: Int, axis: Int): Int {
        val negative = if (axis == 0) RetroPadButton.LEFT else RetroPadButton.UP
        val positive = if (axis == 0) RetroPadButton.RIGHT else RetroPadButton.DOWN
        return when {
            buttons and negative.mask != 0 && buttons and positive.mask == 0 -> -ANALOG_MAX
            buttons and positive.mask != 0 && buttons and negative.mask == 0 -> ANALOG_MAX
            else -> 0
        }
    }

    // Touch/Companion axes already have a configurable radial deadzone. Applying the
    // hardware deadzone again would discard their fine travel (including a user-selected 0%).
    private fun virtualStick(value: Float): Int =
        if (!value.isFinite()) 0 else (value.coerceIn(-1f, 1f) * ANALOG_MAX).roundToInt()

    private fun stick(value: Float): Int =
        if (!value.isFinite() || abs(value) < STICK_DEADZONE) 0 else (value.coerceIn(-1f, 1f) * ANALOG_MAX).roundToInt()

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
