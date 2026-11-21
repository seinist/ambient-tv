package com.ambient.tv.companion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlinx.coroutines.delay

/** The core receives a single normalized frame, not the dimensions of this remote handset. */
internal fun companionPointerCoordinates(x: Float, y: Float, region: String): Pair<Float, Float> {
    val nx = x.coerceIn(0f, 1f)
    val ny = y.coerceIn(0f, 1f)
    return when (region) {
        "lower" -> nx to (0.5f + ny * 0.5f)
        "upper" -> nx to (ny * 0.5f)
        "left" -> (nx * 0.5f) to ny
        "right" -> (0.5f + nx * 0.5f) to ny
        else -> nx to ny
    }
}

internal fun companionDefaultPointerRegion(systemId: String?): String =
    if (systemId in setOf("nds", "n3ds", "wiiu")) "lower" else "full"

/**
 * A dedicated touchpad surface within the FULL-PAGE phone controller. No modal surface,
 * no emulated video stream. All contacts are packed to consecutive Libretro indices 0..3.
 * Single-tap in mouse mode emits real DOWN/UP; relative movement stays separate from taps.
 */
@Composable
internal fun CompanionGamePointerPad(
    kind: String,
    region: String,
    enabled: Boolean,
    onPointer: (CompanionPointerState?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSend by rememberUpdatedState(onPointer)
    val tint = MaterialTheme.colorScheme.primary
    DisposableEffect(kind, region, enabled) {
        onDispose { currentSend(null) }
    }
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .pointerInput(kind, region, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    val pressed = LinkedHashMap<PointerId, Offset>()
                    pressed[down.id] = down.position
                    var last = down.position
                    val started = SystemClock.uptimeMillis()
                    var movement = 0f

                    fun mapped(point: Offset): CompanionPointerContact {
                        val (x, y) = companionPointerCoordinates(point.x / width, point.y / height, region)
                        return CompanionPointerContact(0, x, y)
                    }
                    fun deliver(x: Float = 0f, y: Float = 0f) {
                        when (kind) {
                            "touch" -> {
                                val contacts = pressed.values.take(4).mapIndexed { index, point ->
                                    mapped(point).copy(index = index)
                                }
                                currentSend(CompanionPointerState(kind = "touch", contacts = contacts))
                            }
                            "gun" -> {
                                val contact = pressed.values.firstOrNull()?.let(::mapped)
                                if (contact != null) currentSend(CompanionPointerState(
                                    kind = "gun", contacts = listOf(contact), buttons = 1,
                                )) else currentSend(CompanionPointerState(kind = "gun"))
                            }
                            "mouse" -> currentSend(CompanionPointerState(
                                kind = "mouse", dx = x.coerceIn(-1f, 1f),
                                dy = y.coerceIn(-1f, 1f),
                            ))
                        }
                    }

                    try {
                        if (kind != "mouse") deliver()
                        while (pressed.isNotEmpty()) {
                            val event = awaitPointerEvent()
                            var dx = 0f
                            var dy = 0f
                            for (change in event.changes) {
                                if (change.id == down.id && change.pressed) {
                                    val d = change.position - last
                                    dx = d.x / width
                                    dy = d.y / height
                                    movement += hypot(d.x, d.y)
                                    last = change.position
                                }
                                if (change.pressed) {
                                    if (change.id in pressed || pressed.size < 4)
                                        pressed[change.id] = change.position
                                } else pressed.remove(change.id)
                                change.consume()
                            }
                            if (kind == "mouse") {
                                if (dx != 0f || dy != 0f) deliver(dx, dy)
                            } else {
                                // A rapid pen tap must cross at least one 60Hz core poll. Libretro
                                // input_state may otherwise see DOWN and UP between two frames.
                                if (pressed.isEmpty()) {
                                    val elapsed = SystemClock.uptimeMillis() - started
                                    if (elapsed < 48L) delay(48L - elapsed)
                                }
                                deliver()
                            }
                        }
                        if (kind == "mouse" && movement < width * 0.025f &&
                            SystemClock.uptimeMillis() - started < 300L) {
                            // Short taps are separate frames; coalescing must preserve the edge.
                            currentSend(CompanionPointerState(kind = "mouse", buttons = 1))
                            delay(48L)
                            currentSend(CompanionPointerState(kind = "mouse", buttons = 0))
                        }
                    } finally {
                        currentSend(null)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                drawLine(tint.copy(alpha = 0.13f),
                    Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
                drawLine(tint.copy(alpha = 0.13f),
                    Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
            }
            drawCircle(tint.copy(alpha = 0.36f), 5.dp.toPx(), center)
        }
        Text(
            stringResource(when (kind) {
                "mouse" -> R.string.companion_gamepad_mouse_hint
                "gun" -> R.string.companion_gamepad_gun_hint
                else -> R.string.companion_gamepad_touch_hint
            }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        )
    }
}

/** Sensor readings are sampled only while a foreground game session opted in. */
internal class CompanionPhoneMotion(context: Context) : SensorEventListener {
    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val availableMask = (if (accel != null) 1 else 0) or (if (gyro != null) 2 else 0)
    private val latest = AtomicReference<CompanionMotionState?>(null)

    fun start() {
        if (availableMask == 0) return
        latest.set(CompanionMotionState(mask = availableMask))
        accel?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        latest.set(null)
    }

    fun snapshot(): CompanionMotionState? = latest.get()

    override fun onSensorChanged(event: SensorEvent) {
        if (latest.get() == null || event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latest.updateAndGet {
                it?.copy(
                    ax = (x / 9.80665f).coerceIn(-16f, 16f),
                    ay = (y / 9.80665f).coerceIn(-16f, 16f),
                    az = (z / 9.80665f).coerceIn(-16f, 16f),
                )
            }
            Sensor.TYPE_GYROSCOPE -> latest.updateAndGet {
                it?.copy(gx = x.coerceIn(-100f, 100f), gy = y.coerceIn(-100f, 100f),
                    gz = z.coerceIn(-100f, 100f))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
internal fun rememberCompanionMotionSource(active: Boolean): CompanionPhoneMotion {
    val context = androidx.compose.ui.platform.LocalContext.current
    val source = remember(context) { CompanionPhoneMotion(context) }
    DisposableEffect(source, active) {
        if (active) source.start()
        onDispose { source.stop() }
    }
    return source
}
