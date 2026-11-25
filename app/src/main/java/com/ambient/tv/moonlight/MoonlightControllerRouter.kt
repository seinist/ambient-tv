package com.ambient.tv.moonlight

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Four-port local Android gamepad/remote -> Moonlight protocol adapter.
 *
 * The owner (fullscreen streaming Activity) diverts ALL events to its UI when the OSD is open.
 * Releasing input on every ownership change prevents stuck remote buttons after Back/Home.
 */
internal class MoonlightControllerRouter(
    private val output: (
        port: Short, mask: Short, buttons: Int, lt: Byte, rt: Byte,
        lx: Short, ly: Short, rx: Short, ry: Short,
    ) -> Unit,
) {
    private data class Pad(
        val port: Int,
        var keys: Int = 0,
        var hat: Int = 0,
        var leftTrigger: Float = 0f,
        var rightTrigger: Float = 0f,
        var lx: Float = 0f,
        var ly: Float = 0f,
        var rx: Float = 0f,
        var ry: Float = 0f,
    )
    private val pads = LinkedHashMap<Int, Pad>()

    fun onKey(event: KeyEvent): Boolean {
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        if (!isController(event.source) && event.keyCode !in DPAD_CODES) return false
        val flag = keyFlag(event.keyCode) ?: return false
        val pad = getPad(event.deviceId) ?: return false
        pad.keys = if (event.action == KeyEvent.ACTION_DOWN) pad.keys or flag else pad.keys and flag.inv()
        send(pad)
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        val pad = getPad(event.deviceId) ?: return false
        fun axis(id: Int): Float = event.getAxisValue(id).coerceIn(-1f, 1f)
        fun deadzone(v: Float) = if (abs(v) < 0.10f) 0f else v
        pad.lx = deadzone(axis(MotionEvent.AXIS_X))
        pad.ly = deadzone(axis(MotionEvent.AXIS_Y))
        val z = axis(MotionEvent.AXIS_Z)
        val rz = axis(MotionEvent.AXIS_RZ)
        val rx = axis(MotionEvent.AXIS_RX)
        val ry = axis(MotionEvent.AXIS_RY)
        pad.rx = deadzone(if (z != 0f || rz != 0f) z else rx)
        pad.ry = deadzone(if (z != 0f || rz != 0f) rz else ry)
        pad.leftTrigger = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE), 0f)
        pad.rightTrigger = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS), 0f)
        pad.hat = 0
        when {
            axis(MotionEvent.AXIS_HAT_X) < -0.5f -> pad.hat = pad.hat or MoonlightPadButtons.LEFT_FLAG
            axis(MotionEvent.AXIS_HAT_X) > 0.5f -> pad.hat = pad.hat or MoonlightPadButtons.RIGHT_FLAG
        }
        when {
            axis(MotionEvent.AXIS_HAT_Y) < -0.5f -> pad.hat = pad.hat or MoonlightPadButtons.UP_FLAG
            axis(MotionEvent.AXIS_HAT_Y) > 0.5f -> pad.hat = pad.hat or MoonlightPadButtons.DOWN_FLAG
        }
        send(pad)
        return true
    }

    /** Clear held states even if Android did not deliver the key-up event. */
    fun releaseAll() {
        pads.values.forEach { pad ->
            output(pad.port.toShort(), activeMask(), 0, 0, 0, 0, 0, 0, 0)
        }
        pads.clear()
    }

    fun removeDevice(deviceId: Int) {
        val pad = pads.remove(deviceId) ?: return
        output(pad.port.toShort(), activeMask(), 0, 0, 0, 0, 0, 0, 0)
        pads.values.forEach(::send)
    }

    private fun getPad(id: Int): Pad? {
        pads[id]?.let { return it }
        val port = (0..3).firstOrNull { slot -> pads.values.none { it.port == slot } } ?: return null
        return Pad(port).also { pads[id] = it }
    }

    private fun activeMask(): Short = pads.values.fold(0) { mask, pad -> mask or (1 shl pad.port) }.toShort()
    private fun stick(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
    private fun trigger(v: Float): Byte = (v.coerceIn(0f, 1f) * 255).toInt().toByte()

    private fun send(pad: Pad) = output(
        pad.port.toShort(), activeMask(), pad.keys or pad.hat,
        trigger(pad.leftTrigger), trigger(pad.rightTrigger),
        stick(pad.lx), stick(pad.ly), stick(pad.rx), stick(pad.ry),
    )

    private fun isController(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun keyFlag(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> MoonlightPadButtons.A_FLAG
        KeyEvent.KEYCODE_BUTTON_B -> MoonlightPadButtons.B_FLAG
        KeyEvent.KEYCODE_BUTTON_X -> MoonlightPadButtons.X_FLAG
        KeyEvent.KEYCODE_BUTTON_Y -> MoonlightPadButtons.Y_FLAG
        KeyEvent.KEYCODE_BUTTON_L1 -> MoonlightPadButtons.LB_FLAG
        KeyEvent.KEYCODE_BUTTON_R1 -> MoonlightPadButtons.RB_FLAG
        KeyEvent.KEYCODE_BUTTON_START -> MoonlightPadButtons.PLAY_FLAG
        KeyEvent.KEYCODE_BUTTON_SELECT -> MoonlightPadButtons.BACK_FLAG
        KeyEvent.KEYCODE_BUTTON_THUMBL -> MoonlightPadButtons.LS_CLK_FLAG
        KeyEvent.KEYCODE_BUTTON_THUMBR -> MoonlightPadButtons.RS_CLK_FLAG
        KeyEvent.KEYCODE_DPAD_UP -> MoonlightPadButtons.UP_FLAG
        KeyEvent.KEYCODE_DPAD_DOWN -> MoonlightPadButtons.DOWN_FLAG
        KeyEvent.KEYCODE_DPAD_LEFT -> MoonlightPadButtons.LEFT_FLAG
        KeyEvent.KEYCODE_DPAD_RIGHT -> MoonlightPadButtons.RIGHT_FLAG
        else -> null
    }

    companion object {
        private val DPAD_CODES = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}


/** Stable GameStream controller button bit positions, matching upstream ControllerPacket. */
private object MoonlightPadButtons {
    const val A_FLAG = 0x1000
    const val B_FLAG = 0x2000
    const val X_FLAG = 0x4000
    const val Y_FLAG = 0x8000
    const val UP_FLAG = 0x0001
    const val DOWN_FLAG = 0x0002
    const val LEFT_FLAG = 0x0004
    const val RIGHT_FLAG = 0x0008
    const val LB_FLAG = 0x0100
    const val RB_FLAG = 0x0200
    const val PLAY_FLAG = 0x0010
    const val BACK_FLAG = 0x0020
    const val LS_CLK_FLAG = 0x0040
    const val RS_CLK_FLAG = 0x0080
}
