package com.ambient.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A time-based remote-control scrub shared by all player backends.
 * Repeated KeyDown events never seek the decoder; only KeyUp commits the preview position.
 * Up/Down chapter navigation and the configured fixed seek distance are intentionally separate.
 */
internal class ProgressiveSeekController(private val clockMs: () -> Long = SystemClock::elapsedRealtime) {
    var previewTargetMs by mutableStateOf<Long?>(null)
        private set
    var active by mutableStateOf(false)
        private set

    private var direction = 0
    private var startMs = 0L
    private var lastMs = 0L
    private var startPositionMs = 0L
    private var durationMs = 0L
    private var travelledMs = 0.0

    fun isPressedIn(candidateDirection: Int): Boolean = active && candidateDirection == direction

    fun press(candidateDirection: Int, currentPositionMs: Long, duration: Long) {
        if (candidateDirection != -1 && candidateDirection != 1) return
        if (duration <= 0L) return
        if (isPressedIn(candidateDirection)) {
            advance()
            return
        }
        cancel()
        direction = candidateDirection
        durationMs = duration
        startPositionMs = currentPositionMs.coerceIn(0L, duration)
        startMs = clockMs()
        lastMs = startMs
        travelledMs = 0.0
        previewTargetMs = (startPositionMs + candidateDirection * TAP_STEP_MS).coerceIn(0L, duration)
        active = true
    }

    /** Integrate a logarithmically increasing speed; never issue a backend seek here. */
    fun advance(now: Long = clockMs()) {
        if (!active) return
        val next = maxOf(now, lastMs)
        val from = (lastMs - startMs - HOLD_DELAY_MS).coerceAtLeast(0L)
        val to = (next - startMs - HOLD_DELAY_MS).coerceAtLeast(0L)
        if (to > from) {
            // Keep the original logarithmic ramp for short holds. After another second,
            // add a progressive late-hold boost: a several-second hold should keep getting
            // faster instead of feeling capped at its initial speed. A tap still moves 5 s.
            // Integrate in bounded slices so long gaps in remote repeats / Compose frames
            // give the same target as the normal 50-ms ticker.
            val logAmplitude = maxOf(LOG_SPEED_MS_PER_SECOND, durationMs / 13.0)
            val speedLimit = (durationMs / MAX_DURATION_SPEED_DIVISOR)
                .coerceIn(MIN_SPEED_MS_PER_SECOND, MAX_SPEED_MS_PER_SECOND).toDouble()
            var elapsed = from
            while (elapsed < to && travelledMs < durationMs) {
                val end = minOf(elapsed + INTEGRATION_STEP_MS, to)
                val midpoint = elapsed + (end - elapsed) / 2.0
                val lateSeconds = (midpoint - LATE_BOOST_START_MS).coerceAtLeast(0.0) / 1_000.0
                val multiplier = 1.0 + LATE_BOOST_STRENGTH * lateSeconds.pow(LATE_BOOST_EXPONENT)
                val velocity = ((BASE_SPEED_MS_PER_SECOND + logAmplitude * ln1p(midpoint / LOG_TIME_SCALE_MS)) * multiplier)
                    .coerceAtMost(speedLimit)
                travelledMs = (travelledMs + velocity * (end - elapsed) / 1_000.0)
                    .coerceAtMost(durationMs.toDouble())
                elapsed = end
            }
            val distance = TAP_STEP_MS + travelledMs.roundToLong()
            previewTargetMs = (startPositionMs + direction * distance).coerceIn(0L, durationMs)
        }
        lastMs = next
    }

    fun release(candidateDirection: Int): Long? {
        if (!isPressedIn(candidateDirection)) return null
        advance()
        val target = previewTargetMs
        cancel()
        return target
    }

    fun cancel() {
        active = false
        previewTargetMs = null
        direction = 0
        travelledMs = 0.0
    }

    private companion object {
        const val TAP_STEP_MS = 5_000L
        const val HOLD_DELAY_MS = 300L
        const val BASE_SPEED_MS_PER_SECOND = 10_000.0
        const val LOG_SPEED_MS_PER_SECOND = 77_000.0
        const val LOG_TIME_SCALE_MS = 900.0
        /** Starts after the initial 300-ms hold delay plus one second of continuous travel. */
        const val LATE_BOOST_START_MS = 1_000.0
        const val LATE_BOOST_STRENGTH = 0.12
        const val LATE_BOOST_EXPONENT = 1.25
        const val INTEGRATION_STEP_MS = 50L
        const val MIN_SPEED_MS_PER_SECOND = 20_000L
        const val MAX_SPEED_MS_PER_SECOND = 1_200_000L
        const val MAX_DURATION_SPEED_DIVISOR = 6L
    }
}

/** One stable timer per fullscreen player. Real holding works even if the remote emits no repeats. */
@Composable
internal fun ProgressiveSeekTicker(controller: ProgressiveSeekController) {
    LaunchedEffect(controller, controller.active) {
        while (controller.active) {
            delay(50L)
            controller.advance()
        }
    }
}
