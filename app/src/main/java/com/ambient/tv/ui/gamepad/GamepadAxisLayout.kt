package com.ambient.tv.ui.gamepad

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Where a controller reports its right stick and analog triggers. With an Android key layout
 * (Xbox, DualShock 4, DualSense, Switch Pro …) the right stick is Z/RZ and the triggers are
 * LTRIGGER/RTRIGGER (or BRAKE/GAS). Controllers without one often use the generic HID order:
 * right stick on RX/RY and the triggers on Z/RZ (range 0…1).
 */
data class GamepadAxisLayout(
    val rightX: Int,
    val rightY: Int,
    val leftTrigger: List<Int>,
    val rightTrigger: List<Int>,
) {
    fun rightStickX(event: MotionEvent) = event.getAxisValue(rightX)
    fun rightStickY(event: MotionEvent) = event.getAxisValue(rightY)
    fun leftTriggerValue(event: MotionEvent) = leftTrigger.maxOf { event.getAxisValue(it) }
    fun rightTriggerValue(event: MotionEvent) = rightTrigger.maxOf { event.getAxisValue(it) }

    companion object {
        val STANDARD = GamepadAxisLayout(
            rightX = MotionEvent.AXIS_Z,
            rightY = MotionEvent.AXIS_RZ,
            leftTrigger = listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
            rightTrigger = listOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
        )

        fun of(device: InputDevice?): GamepadAxisLayout {
            device ?: return STANDARD
            fun range(axis: Int) = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            return forRanges(
                hasZ = range(MotionEvent.AXIS_Z) != null,
                zMin = range(MotionEvent.AXIS_Z)?.min,
                rzMin = range(MotionEvent.AXIS_RZ)?.min,
                hasRx = range(MotionEvent.AXIS_RX) != null && range(MotionEvent.AXIS_RY) != null,
            )
        }

        /** Pure decision, testable without a device: a 0…1 Z axis is a trigger, not a stick. */
        internal fun forRanges(hasZ: Boolean, zMin: Float?, rzMin: Float?, hasRx: Boolean): GamepadAxisLayout {
            val zIsTrigger = zMin != null && zMin >= 0f
            if (!hasRx || (hasZ && !zIsTrigger)) return STANDARD
            return GamepadAxisLayout(
                rightX = MotionEvent.AXIS_RX,
                rightY = MotionEvent.AXIS_RY,
                leftTrigger = STANDARD.leftTrigger + listOfNotNull(MotionEvent.AXIS_Z.takeIf { zIsTrigger }),
                rightTrigger = STANDARD.rightTrigger + listOfNotNull(MotionEvent.AXIS_RZ.takeIf { rzMin != null && rzMin >= 0f }),
            )
        }
    }
}
