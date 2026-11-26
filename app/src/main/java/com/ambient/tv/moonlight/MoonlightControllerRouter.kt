package com.ambient.tv.moonlight

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ambient.tv.ui.gamepad.GamepadAxisLayout
import com.ambient.tv.ui.gamepad.retro.RetroInputKind
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.retroInputDevice
import kotlin.math.abs

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
) {
    private data class Pad(
        val port: Int,
        val deviceId: Int,
        val layout: GamepadAxisLayout,
        val keys: MutableMap<Int, RetroPadButton> = mutableMapOf(),
        val suppressed: MutableSet<Int> = mutableSetOf(),
        var hat: Int = 0,
        var leftTrigger: Float = 0f,
        var rightTrigger: Float = 0f,
        var lx: Float = 0f,
        var ly: Float = 0f,
        var rx: Float = 0f,
        var ry: Float = 0f,
    )
    private val pads = LinkedHashMap<Int, Pad>()

    private fun button(deviceId: Int, key: Int): RetroPadButton? {
        val info = retroInputDevice(deviceId)
        if (info.kind == RetroInputKind.GAMEPAD) {
            val profile = RetroInputSettings.config.deviceProfile(info.kind, info.descriptor)
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
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        if (event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        if (!isController(event.source) && event.keyCode !in DPAD_CODES) return false
        val info = retroInputDevice(event.deviceId)
        val profile = RetroInputSettings.config.deviceProfile(info.kind, info.descriptor)
        if (info.kind == RetroInputKind.GAMEPAD && profile.opensMenu(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onMenu()
            return true
        }
        val mapped = button(event.deviceId, event.keyCode)
        val pad = pads[event.deviceId] ?: if (mapped != null) getPad(event.deviceId) else null
        if (pad == null) return false
        if (event.action == KeyEvent.ACTION_UP) {
            val held = pad.keys.remove(event.keyCode)
            val suppressed = pad.suppressed.remove(event.keyCode)
            if (held == null && !suppressed && mapped == null) return false
        } else {
            if (event.repeatCount != 0 || event.keyCode in pad.suppressed) return true
            if (mapped == null) return false
            pad.keys[event.keyCode] = mapped
        }
        send(pad)
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val pad = getPad(event.deviceId) ?: return false
        fun axis(id: Int): Float = event.getAxisValue(id).takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        fun deadzone(value: Float) = if (!value.isFinite() || abs(value) < 0.10f) 0f else value.coerceIn(-1f, 1f)
        pad.lx = deadzone(axis(MotionEvent.AXIS_X))
        pad.ly = deadzone(axis(MotionEvent.AXIS_Y))
        pad.rx = deadzone(pad.layout.rightStickX(event))
        pad.ry = deadzone(pad.layout.rightStickY(event))
        pad.leftTrigger = pad.layout.leftTriggerValue(event).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        pad.rightTrigger = pad.layout.rightTriggerValue(event).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        var hat = 0
        fun add(key: Int) { button(event.deviceId, key)?.let { hat = hat or it.mask } }
        if (axis(MotionEvent.AXIS_HAT_X) < -0.5f) add(KeyEvent.KEYCODE_DPAD_LEFT)
        if (axis(MotionEvent.AXIS_HAT_X) > 0.5f) add(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (axis(MotionEvent.AXIS_HAT_Y) < -0.5f) add(KeyEvent.KEYCODE_DPAD_UP)
        if (axis(MotionEvent.AXIS_HAT_Y) > 0.5f) add(KeyEvent.KEYCODE_DPAD_DOWN)
        pad.hat = hat
        send(pad)
        return true
    }

    fun registerAttachedDevices() {
        InputDevice.getDeviceIds().forEach { id ->
            val device = InputDevice.getDevice(id) ?: return@forEach
            if (!device.isVirtual && retroInputDevice(id).kind == RetroInputKind.GAMEPAD) getPad(id)
        }
    }
    fun localUsedPorts(): Set<Int> = pads.values.mapTo(HashSet()) { it.port }
    fun deviceIdForPort(port: Int): Int? = pads.values.firstOrNull { it.port == port }?.deviceId
    fun capabilities(port: Int): MoonlightPadCapabilities {
        val device = deviceIdForPort(port)?.let { InputDevice.getDevice(it) } ?: return MoonlightPadCapabilities()
        return MoonlightPadCapabilities(rumble = runCatching { device.vibrator.hasVibrator() }.getOrDefault(false))
    }

    /** Neutralize all input without changing physical player assignments. */
    fun releaseAll() {
        for (pad in pads.values) {
            pad.suppressed.addAll(pad.keys.keys)
            pad.keys.clear()
            pad.hat = 0
            pad.leftTrigger = 0f
            pad.rightTrigger = 0f
            pad.lx = 0f; pad.ly = 0f; pad.rx = 0f; pad.ry = 0f
            send(pad)
        }
    }

    fun republish() { pads.values.forEach(::send) }

    fun removeDevice(deviceId: Int) {
        val pad = pads.remove(deviceId) ?: return
        output(pad.port.toShort(), activeMask(), 0, 0, 0, 0, 0, 0, 0)
        pads.values.forEach(::send)
    }

    private fun getPad(id: Int): Pad? {
        pads[id]?.let { return it }
        val info = retroInputDevice(id)
        val config = RetroInputSettings.config
        val assignment = config.ports[info.descriptor] ?: RetroPorts.AUTO
        if (assignment == RetroPorts.OFF) return null
        val blocked = reservedPorts() + localUsedPorts()
        val fixed = config.ports.values.filter { it in 0..3 }.toSet()
        val port = if (assignment in 0..3) assignment.takeUnless { it in blocked }
            else (0..3).firstOrNull { it !in blocked && it !in fixed }
        return port?.let { Pad(it, id, GamepadAxisLayout.of(InputDevice.getDevice(id))).also { pad -> pads[id] = pad } }
    }

    private fun activeMask(): Short = pads.values.fold(0) { mask, pad -> mask or (1 shl pad.port) }.toShort()
    private fun stick(v: Float): Short = moonlightStick(v)
    private fun trigger(v: Float): Byte = (v.coerceIn(0f, 1f) * 255).toInt().toByte()

    private fun send(pad: Pad) {
        val buttons = pad.keys.values.fold(pad.hat) { mask, button -> mask or button.mask }
        val lt = maxOf(pad.leftTrigger, if (buttons and RetroPadButton.L2.mask != 0) 1f else 0f)
        val rt = maxOf(pad.rightTrigger, if (buttons and RetroPadButton.R2.mask != 0) 1f else 0f)
        output(pad.port.toShort(), activeMask(), moonlightRetroButtonFlags(buttons),
            trigger(lt), trigger(rt), stick(pad.lx), stick(-pad.ly), stick(pad.rx), stick(-pad.ry))
    }

    private fun isController(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    companion object {
        private val DPAD_CODES = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
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
