package com.ambient.tv.ui.games

import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.view.InputDevice

/**
 * Vibration of one controller as a libretro core asks for it: strengths 0…1 of the weak and the
 * strong motor. LibretroDroid reports changes only, so a strength holds until the next one (at most
 * [HOLD_MS], in case a stop gets lost); 0 stops at once.
 */
internal object GameRumble {
    private const val HOLD_MS = 10_000L
    private val activeDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun apply(deviceId: Int, weak: Float, strong: Float) {
        val strength = maxOf(weak, strong).coerceIn(0f, 1f)
        if (strength <= 0f) activeDevices.remove(deviceId) else activeDevices.add(deviceId)
        val device = InputDevice.getDevice(deviceId) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = device.vibratorManager
                if (manager.vibratorIds.isEmpty()) return
                if (strength <= 0f) {
                    manager.cancel()
                    return
                }
                val amplitude = (strength * 255).toInt().coerceIn(1, 255)
                manager.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(HOLD_MS, amplitude)))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = device.vibrator
                if (!vibrator.hasVibrator()) return
                if (strength <= 0f) {
                    vibrator.cancel()
                    return
                }
                val amplitude = if (vibrator.hasAmplitudeControl()) (strength * 255).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                vibrator.vibrate(VibrationEffect.createOneShot(HOLD_MS, amplitude))
            }
        }
    }

    fun stop(deviceIds: Collection<Int>) = deviceIds.forEach { apply(it, 0f, 0f) }
    fun stopAll() = stop(activeDevices.toList())
}
