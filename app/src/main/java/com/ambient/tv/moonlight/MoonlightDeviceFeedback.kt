package com.ambient.tv.moonlight

import android.os.SystemClock
import android.os.VibrationEffect
import android.view.InputDevice

/**
 * Hardware vibration mapped to the physical Android controller's own vibrator.
 * No phone UI haptic or TV haptic is synthesized for missing/non-vibrating devices.
 */
internal class MoonlightDeviceFeedback(private val controls: MoonlightControllerRouter) {
    private val vibrating = HashSet<Int>()
    private val lastAt = HashMap<Int, Long>()
    private val lastLevel = HashMap<Int, Int>()

    fun rumble(event: MoonlightRumble) {
        if (event.port !in 0..3) return
        val deviceId = controls.deviceIdForPort(event.port) ?: return
        val vibrator = InputDevice.getDevice(deviceId)?.vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val low = event.low.coerceIn(0, 65535)
        val high = event.high.coerceIn(0, 65535)
        val level = (maxOf(low, high) * 255L / 65535L).toInt()
        if (level == 0) {
            if (vibrating.remove(deviceId)) runCatching { vibrator.cancel() }
            lastLevel.remove(deviceId)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastLevel[deviceId] == level && now - (lastAt[deviceId] ?: 0L) < 75L) return
        lastAt[deviceId] = now
        lastLevel[deviceId] = level
        vibrating.add(deviceId)
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(130, level.coerceIn(1, 255)))
        }
    }

    fun cancelAll() {
        vibrating.forEach { deviceId ->
            InputDevice.getDevice(deviceId)?.vibrator?.let { runCatching { it.cancel() } }
        }
        vibrating.clear()
        lastAt.clear()
        lastLevel.clear()
    }
}
