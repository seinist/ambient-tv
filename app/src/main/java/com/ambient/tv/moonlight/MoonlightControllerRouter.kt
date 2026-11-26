package com.ambient.tv.moonlight

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.gamepad.GamepadAxisLayout
import com.ambient.tv.ui.gamepad.retro.RetroInputKind
import com.ambient.tv.ui.gamepad.retro.RetroInputDevice
import com.ambient.tv.ui.gamepad.retro.RetroInputProfile
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.retroInputDevice
import kotlin.math.abs
import com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext
import android.os.Handler
import android.os.Looper

data class MoonlightPadCapabilities(
    val buttons: Int = 0xF3FF,
    val analogTriggers: Boolean = true,
    val rumble: Boolean = false,
    val accelerometer: Boolean = false,
    val gyroscope: Boolean = false,
)

/** Main-thread physical controller routing using Ambient's existing device profiles and axis layout. */
internal class MoonlightControllerRouter(
    private val output: (
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ) -> Unit,
    private val reservedPorts: () -> Set<Int> = { emptySet() },
    private val onMenu: () -> Unit = {},
    private val profileContext: () -> RetroInputProfileContext? = { null },
) {
    private data class Pad(
        val port: Int,
        val deviceId: Int,
        val layout: GamepadAxisLayout,
        val capabilities: MoonlightPadCapabilities,
        val stickDeadZones: FloatArray,
        val keys: MutableMap<Int, RetroPadButton> = mutableMapOf(),
        val suppressed: MutableSet<Int> = mutableSetOf(),
        var hat: Int = 0,
        var rawLeftTrigger: Boolean = false,
        var rawRightTrigger: Boolean = false,
        var suppressMotion: Boolean = false,
        val pendingMenu: MutableMap<Int, Runnable> = mutableMapOf(),
        var leftTrigger: Float = 0f,
        var rightTrigger: Float = 0f,
        var lx: Float = 0f,
        var ly: Float = 0f,
        var rx: Float = 0f,
        var ry: Float = 0f,
        var lastSent: WireState? = null,
    )
    private data class WireState(val mask: Short, val buttons: Int, val lt: Byte, val rt: Byte,
                                 val lx: Short, val ly: Short, val rx: Short, val ry: Short)
    private val pads = LinkedHashMap<Int, Pad>()
    private val devices = HashMap<Int, RetroInputDevice>()
    private val profiles = HashMap<Int, RetroInputProfile>()
    private fun cachedDevice(id: Int) = devices.getOrPut(id) { retroInputDevice(id) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastConfig = RetroInputSettings.config
    private var lastContext = profileContext()

    private fun profile(deviceId: Int): RetroInputProfile = profiles.getOrPut(deviceId) {
        val info = cachedDevice(deviceId)
        lastContext?.let { lastConfig.profile(info.kind, it, deviceDescriptor = info.descriptor) }
            ?: lastConfig.deviceProfile(info.kind, info.descriptor)
    }

    /** A changed port or profile neutralizes old state before reallocating devices. */
    fun refreshAssignments() {
        val config = RetroInputSettings.config
        val context = profileContext()
        val reserved = reservedPorts()
        if (lastConfig === config && lastContext == context && pads.values.none { it.port in reserved }) return
        lastConfig = config
        lastContext = context
        profiles.clear()
        val old = pads.values.toList()
        releaseAll()
        pads.clear()
        old.forEach { output(it.port.toShort(), 0, 0, 0, 0, 0, 0, 0, 0) }
        registerAttachedDevices()
        pads.values.forEach { it.suppressMotion = true }
        republish()
    }

    private fun button(deviceId: Int, key: Int): RetroPadButton? {
        val info = cachedDevice(deviceId)
        if (info.kind == RetroInputKind.GAMEPAD) {
            val profile = profile(deviceId)
            return profile.button(key)
        }
        return when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> RetroPadButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RetroPadButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> RetroPadButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RetroPadButton.RIGHT
            else -> null
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        refreshAssignments()
        if (event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        if (!isController(event.source) && event.keyCode !in DPAD_CODES) return false
        val info = cachedDevice(event.deviceId)
        val profile = profile(event.deviceId)
        if (info.kind == RetroInputKind.GAMEPAD && profile.opensMenu(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onMenu()
            return true
        }
        val mapped = button(event.deviceId, event.keyCode)
        // A gameplay button must not wait for a 500ms menu decision. Dedicated hold-menu
        // keys remain available; ambiguous imported mappings prioritize gameplay.
        val holdMenu = mapped == null && info.kind == RetroInputKind.GAMEPAD && profile.holdOpensMenu(event.keyCode)
        val pad = pads[event.deviceId] ?: if (mapped != null || holdMenu) getPad(event.deviceId) else null
        if (pad == null) return false
        if (holdMenu) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val key = event.keyCode
                val task = Runnable {
                    if (pad.pendingMenu.remove(key) != null) {
                        pad.suppressed.add(key)
                        onMenu()
                    }
                }
                pad.pendingMenu.put(key, task)?.let(handler::removeCallbacks)
                handler.postDelayed(task, 500)
            } else if (event.action == KeyEvent.ACTION_UP) {
                pad.pendingMenu.remove(event.keyCode)?.let(handler::removeCallbacks)
                pad.suppressed.remove(event.keyCode)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val held = pad.keys.remove(event.keyCode)
            val suppressed = pad.suppressed.remove(event.keyCode)
            if (held == null && !suppressed && mapped == null) return false
        } else {
            if (event.repeatCount != 0) return true
            pad.suppressed.remove(event.keyCode)
            if (mapped == null) return false
            pad.keys[event.keyCode] = mapped
            if (info.kind == RetroInputKind.GAMEPAD &&
                RetroInputSettings.config.menuCombo.matches(pad.keys.values.fold(pad.hat) { bits, b -> bits or b.mask })) {
                onMenu()
                return true
            }
        }
        send(pad)
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        refreshAssignments()
        val pad = getPad(event.deviceId) ?: return false
        fun axis(id: Int): Float = event.getAxisValue(id).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        pad.lx = moonlightDeadzone(axis(MotionEvent.AXIS_X), pad.stickDeadZones[0])
        pad.ly = moonlightDeadzone(axis(MotionEvent.AXIS_Y), pad.stickDeadZones[1])
        pad.rx = moonlightDeadzone(pad.layout.rightStickX(event), pad.stickDeadZones[2])
        pad.ry = moonlightDeadzone(pad.layout.rightStickY(event), pad.stickDeadZones[3])
        val left = pad.layout.leftTriggerValue(event).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val right = pad.layout.rightTriggerValue(event).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        pad.rawLeftTrigger = pad.rawLeftTrigger || left > .01f
        pad.rawRightTrigger = pad.rawRightTrigger || right > .01f
        pad.leftTrigger = 0f
        pad.rightTrigger = 0f
        var hat = 0
        fun mapTrigger(key: Int, pressure: Float) {
            when (val target = button(event.deviceId, key)) {
                RetroPadButton.L2 -> pad.leftTrigger = maxOf(pad.leftTrigger, pressure)
                RetroPadButton.R2 -> pad.rightTrigger = maxOf(pad.rightTrigger, pressure)
                else -> if (target != null && pressure >= .5f) hat = hat or target.mask
            }
        }
        mapTrigger(KeyEvent.KEYCODE_BUTTON_L2, left)
        mapTrigger(KeyEvent.KEYCODE_BUTTON_R2, right)
        fun add(key: Int) { button(event.deviceId, key)?.let { hat = hat or it.mask } }
        if (axis(MotionEvent.AXIS_HAT_X) < -0.5f) add(KeyEvent.KEYCODE_DPAD_LEFT)
        if (axis(MotionEvent.AXIS_HAT_X) > 0.5f) add(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (axis(MotionEvent.AXIS_HAT_Y) < -0.5f) add(KeyEvent.KEYCODE_DPAD_UP)
        if (axis(MotionEvent.AXIS_HAT_Y) > 0.5f) add(KeyEvent.KEYCODE_DPAD_DOWN)
        pad.hat = hat
        if (pad.suppressMotion) {
            if (pad.lx == 0f && pad.ly == 0f && pad.rx == 0f && pad.ry == 0f && left < .01f && right < .01f && hat == 0)
                pad.suppressMotion = false
            else {
                pad.lx = 0f; pad.ly = 0f; pad.rx = 0f; pad.ry = 0f
                pad.leftTrigger = 0f; pad.rightTrigger = 0f; pad.hat = 0
            }
        }
        send(pad)
        return true
    }

    fun registerAttachedDevices() {
        InputDevice.getDeviceIds().forEach { id ->
            val device = InputDevice.getDevice(id) ?: return@forEach
            if (!device.isVirtual && cachedDevice(id).kind == RetroInputKind.GAMEPAD) getPad(id)
        }
    }
    fun localUsedPorts(): Set<Int> = pads.values.mapTo(HashSet()) { it.port }
    fun deviceIdForPort(port: Int): Int? = pads.values.firstOrNull { it.port == port }?.deviceId
    fun capabilities(port: Int): MoonlightPadCapabilities =
        pads.values.firstOrNull { it.port == port }?.capabilities ?: MoonlightPadCapabilities()

    /** Neutralize all input without changing physical player assignments. */
    fun releaseAll() {
        for (pad in pads.values) {
            pad.suppressed.addAll(pad.keys.keys)
            pad.pendingMenu.values.forEach(handler::removeCallbacks)
            pad.pendingMenu.clear()
            pad.keys.clear()
            pad.suppressMotion = true
            pad.hat = 0
            pad.leftTrigger = 0f
            pad.rightTrigger = 0f
            pad.lx = 0f; pad.ly = 0f; pad.rx = 0f; pad.ry = 0f
            send(pad, force = true)
        }
    }

    fun republish() { pads.values.forEach { send(it, force = true) } }

    fun removeDevice(deviceId: Int) {
        devices.remove(deviceId)
        profiles.remove(deviceId)
        val pad = pads.remove(deviceId) ?: return
        pad.pendingMenu.values.forEach(handler::removeCallbacks)
        output(pad.port.toShort(), activeMask(), 0, 0, 0, 0, 0, 0, 0)
        republish()
    }

    private fun getPad(id: Int): Pad? {
        pads[id]?.let { return it }
        val info = cachedDevice(id)
        val config = lastConfig
        val assignment = config.ports[info.descriptor] ?: RetroPorts.AUTO
        if (assignment == RetroPorts.OFF) return null
        val blocked = reservedPorts() + localUsedPorts()
        val fixed = config.ports.values.filter { it in 0..3 }.toSet()
        val port = if (assignment in 0..3) assignment.takeUnless { it in blocked }
            else (0..3).firstOrNull { it !in blocked && it !in fixed }
        return port?.let {
            val inputDevice = InputDevice.getDevice(id)
            val caps = MoonlightPadCapabilities(rumble = runCatching { inputDevice?.vibrator?.hasVibrator() == true }.getOrDefault(false))
            val layout = GamepadAxisLayout.of(inputDevice)
            val deadZones = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, layout.rightX, layout.rightY)
                .map { axis ->
                    inputDevice?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)?.flat
                        ?.takeIf { it.isFinite() && it >= 0f }?.coerceIn(.02f, .20f) ?: .10f
                }.toFloatArray()
            Pad(it, id, layout, caps, deadZones).also { pad -> pads[id] = pad }
        }
    }

    private fun activeMask(): Short = pads.values.fold(0) { mask, pad -> mask or (1 shl pad.port) }.toShort()
    private fun stick(v: Float): Short = moonlightStick(v)
    private fun trigger(v: Float): Byte = (v.coerceIn(0f, 1f) * 255).toInt().toByte()

    private fun send(pad: Pad, force: Boolean = false) {
        val buttons = pad.keys.entries.fold(pad.hat) { mask, (key, button) ->
            if (key == KeyEvent.KEYCODE_BUTTON_L2 && pad.rawLeftTrigger ||
                key == KeyEvent.KEYCODE_BUTTON_R2 && pad.rawRightTrigger) mask else mask or button.mask
        }
        val lt = maxOf(pad.leftTrigger, if (buttons and RetroPadButton.L2.mask != 0) 1f else 0f)
        val rt = maxOf(pad.rightTrigger, if (buttons and RetroPadButton.R2.mask != 0) 1f else 0f)
        val state = WireState(activeMask(), moonlightRetroButtonFlags(buttons),
            trigger(lt), trigger(rt), stick(pad.lx), stick(-pad.ly), stick(pad.rx), stick(-pad.ry))
        if (!force && state == pad.lastSent) return
        pad.lastSent = state
        output(pad.port.toShort(), state.mask, state.buttons, state.lt, state.rt,
            state.lx, state.ly, state.rx, state.ry)
    }

    private fun isController(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    companion object {
        private val DPAD_CODES = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}

internal fun moonlightDeadzone(value: Float, flat: Float): Float {
    if (!value.isFinite()) return 0f
    val zone = flat.takeIf { it.isFinite() }?.coerceIn(.02f, .20f) ?: .10f
    val magnitude = abs(value).coerceAtMost(1f)
    if (magnitude <= zone) return 0f
    val scaled = (magnitude - zone) / (1f - zone)
    return if (value < 0f) -scaled else scaled
}

internal fun moonlightStick(v: Float): Short =
    ((if (v.isFinite()) v.coerceIn(-1f, 1f) else 0f) * 32767).toInt().toShort()

/** Shared, pure conversion used by unit tests and Companion deadman release. */
internal fun moonlightRetroButtonFlags(bits: Int): Int {
    fun held(button: RetroPadButton) = bits and button.mask != 0
    var out = 0
    if (held(RetroPadButton.B)) out = out or 0x1000 // Xbox A
    if (held(RetroPadButton.A)) out = out or 0x2000 // Xbox B
    if (held(RetroPadButton.Y)) out = out or 0x4000 // Xbox X
    if (held(RetroPadButton.X)) out = out or 0x8000 // Xbox Y
    if (held(RetroPadButton.UP)) out = out or 0x0001
    if (held(RetroPadButton.DOWN)) out = out or 0x0002
    if (held(RetroPadButton.LEFT)) out = out or 0x0004
    if (held(RetroPadButton.RIGHT)) out = out or 0x0008
    if (held(RetroPadButton.L)) out = out or 0x0100
    if (held(RetroPadButton.R)) out = out or 0x0200
    if (held(RetroPadButton.START)) out = out or 0x0010
    if (held(RetroPadButton.SELECT)) out = out or 0x0020
    if (held(RetroPadButton.L3)) out = out or 0x0040
    if (held(RetroPadButton.R3)) out = out or 0x0080
    return out
}
