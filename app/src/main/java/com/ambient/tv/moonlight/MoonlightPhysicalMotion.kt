package com.ambient.tv.moonlight

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.InputDevice

internal fun moonlightAccelerationFromG(value: Float): Float = value * 9.80665f
internal fun moonlightGyroDegrees(value: Float): Float = Math.toDegrees(value.toDouble()).toFloat()

/** Main-thread, host-requested sensor subscriptions; never use the phone sensor in place of
 * a physical gamepad. Android 12's input-device sensor implementation is excluded conservatively. */
internal class MoonlightPhysicalMotion(
    private val deviceForPort: (Int) -> Int?,
    private val requestedRate: (Int, Byte) -> Int,
    private val send: (Int, Byte, Float, Float, Float) -> Unit,
) {
    private data class Key(val port: Int, val device: Int, val type: Byte, val rate: Int)
    private data class Subscription(val manager: SensorManager, val listener: SensorEventListener)
    private val subscriptions = mutableMapOf<Key, Subscription>()

    fun refresh() {
        if (Build.VERSION.SDK_INT < 33) return
        val desired = buildSet {
            for (port in 0..3) {
                val device = deviceForPort(port) ?: continue
                for (type in listOf(1.toByte(), 2.toByte())) {
                    val rate = requestedRate(port, type).coerceIn(0, 120)
                    if (rate > 0) add(Key(port, device, type, rate))
                }
            }
        }
        for (key in subscriptions.keys.toList()) {
            if (key !in desired) remove(key)
        }
        for (key in desired) {
            if (key in subscriptions) continue
            val manager = runCatching { InputDevice.getDevice(key.device)?.sensorManager }.getOrNull() ?: continue
            val sensorType = if (key.type == 1.toByte()) Sensor.TYPE_ACCELEROMETER else Sensor.TYPE_GYROSCOPE
            val sensor = runCatching { manager.getDefaultSensor(sensorType) }.getOrNull() ?: continue
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onSensorChanged(event: SensorEvent) {
                    if (subscriptions[key]?.listener !== this || deviceForPort(key.port) != key.device ||
                        requestedRate(key.port, key.type) <= 0 || event.values.size < 3) return
                    val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
                    if (key.type == 2.toByte())
                        send(key.port, key.type, moonlightGyroDegrees(x), moonlightGyroDegrees(y), moonlightGyroDegrees(z))
                    else send(key.port, key.type, x, y, z)
                }
            }
            if (runCatching { manager.registerListener(listener, sensor, 1_000_000 / key.rate) }.getOrDefault(false))
                subscriptions[key] = Subscription(manager, listener)
        }
    }

    private fun remove(key: Key) {
        val subscription = subscriptions.remove(key) ?: return
        runCatching { subscription.manager.unregisterListener(subscription.listener) }
    }

    fun stop() { subscriptions.keys.toList().forEach(::remove) }

    companion object {
        fun availableMask(device: InputDevice?): Int {
            if (Build.VERSION.SDK_INT < 33 || device == null) return 0
            return runCatching {
                val manager = device.sensorManager
                (if (manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) 1 else 0) or
                    (if (manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) 2 else 0)
            }.getOrDefault(0)
        }
    }
}
