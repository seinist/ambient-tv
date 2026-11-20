package com.ambient.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.ln1p
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
            val midpoint = (from + (to - from) / 2).toDouble()
            val velocity = (BASE_SPEED_MS_PER_SECOND + LOG_SPEED_MS_PER_SECOND * ln1p(midpoint / LOG_TIME_SCALE_MS))
                .coerceAtMost((durationMs / MAX_DURATION_SPEED_DIVISOR).coerceIn(MIN_SPEED_MS_PER_SECOND, MAX_SPEED_MS_PER_SECOND).toDouble())
            travelledMs += velocity * (to - from) / 1_000.0
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
        const val MIN_SPEED_MS_PER_SECOND = 20_000L
        const val MAX_SPEED_MS_PER_SECOND = 180_000L
        const val MAX_DURATION_SPEED_DIVISOR = 40L
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
