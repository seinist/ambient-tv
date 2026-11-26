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
    )
    private val pads = LinkedHashMap<Int, Pad>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastConfig = RetroInputSettings.config
    private var lastContext = profileContext()

    private fun profile(deviceId: Int): com.ambient.tv.ui.gamepad.retro.RetroInputProfile {
        val info = retroInputDevice(deviceId)
        val config = RetroInputSettings.config
        return profileContext()?.let { config.profile(info.kind, it, deviceDescriptor = info.descriptor) }
            ?: config.deviceProfile(info.kind, info.descriptor)
    }

    /** A changed port or profile neutralizes old state before reallocating devices. */
    fun refreshAssignments() {
        val config = RetroInputSettings.config
        val context = profileContext()
        val reserved = reservedPorts()
        if (lastConfig == config && lastContext == context && pads.values.none { it.port in reserved }) return
        lastConfig = config
        lastContext = context
        val old = pads.values.toList()
        releaseAll()
        pads.clear()
        old.forEach { output(it.port.toShort(), 0, 0, 0, 0, 0, 0, 0, 0) }
        registerAttachedDevices()
        pads.values.forEach { it.suppressMotion = true }
        republish()
    }

    private fun button(deviceId: Int, key: Int): RetroPadButton? {
        val info = retroInputDevice(deviceId)
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
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        refreshAssignments()
        if (event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        if (!isController(event.source) && event.keyCode !in DPAD_CODES) return false
        val info = retroInputDevice(event.deviceId)
        val profile = profile(event.deviceId)
        if (info.kind == RetroInputKind.GAMEPAD && profile.opensMenu(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onMenu()
            return true
        }
        val mapped = button(event.deviceId, event.keyCode)
        val holdMenu = info.kind == RetroInputKind.GAMEPAD && profile.holdOpensMenu(event.keyCode)
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
                val pending = pad.pendingMenu.remove(event.keyCode)
                pending?.let(handler::removeCallbacks)
                if (pending != null && mapped != null && !pad.suppressed.remove(event.keyCode)) {
                    pad.keys[event.keyCode] = mapped
                    send(pad)
                    pad.keys.remove(event.keyCode)
                    send(pad)
                }
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
        fun deadzone(value: Float) = if (!value.isFinite() || abs(value) < 0.10f) 0f else value.coerceIn(-1f, 1f)
        pad.lx = deadzone(axis(MotionEvent.AXIS_X))
        pad.ly = deadzone(axis(MotionEvent.AXIS_Y))
        pad.rx = deadzone(pad.layout.rightStickX(event))
        pad.ry = deadzone(pad.layout.rightStickY(event))
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
            if (maxOf(abs(pad.lx), abs(pad.ly), abs(pad.rx), abs(pad.ry), left, right) < .12f && hat == 0)
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
            pad.pendingMenu.values.forEach(handler::removeCallbacks)
            pad.pendingMenu.clear()
            pad.keys.clear()
            pad.suppressMotion = true
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
        pad.pendingMenu.values.forEach(handler::removeCallbacks)
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
        val buttons = pad.keys.entries.fold(pad.hat) { mask, (key, button) ->
            if (key == KeyEvent.KEYCODE_BUTTON_L2 && pad.rawLeftTrigger ||
                key == KeyEvent.KEYCODE_BUTTON_R2 && pad.rawRightTrigger) mask else mask or button.mask
        }
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
