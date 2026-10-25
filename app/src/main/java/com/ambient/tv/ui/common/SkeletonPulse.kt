package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.ambient.tv.data.browse.BrowseWorkBudget
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Placeholder alpha curve: 0.28..0.62 around [REST_ALPHA], back at rest every half period,
 *  so starting and settling never make the placeholder jump. */
internal object SkeletonPulseCurve {
    const val REST_ALPHA = 0.45f
    const val AMPLITUDE = 0.17f
    const val PERIOD_MS = 1_800L

    fun alpha(pulsedMs: Long): Float {
        val phase = (pulsedMs.coerceAtLeast(0L) % PERIOD_MS).toFloat() / PERIOD_MS
        return REST_ALPHA + AMPLITUDE * kotlin.math.sin(2.0 * Math.PI * phase).toFloat()
    }
}

internal enum class PulsePhase { WAIT, PULSE, PAUSE, SETTLED }

/**
 * Damped pulse for single loading images: static for [startDelayMs] (short loads never pulse),
 * frozen while the user navigates, and static at rest once [pulseBudgetMs] of animation ran,
 * after [settleAfterMs] once input arrives, or at [hardStopMs] at the latest.
 */
internal class DampedPulseTimeline(
    val startDelayMs: Long = 400L,
    val pulseBudgetMs: Long = 3_600L,
    val settleAfterMs: Long = 4_000L,
    val hardStopMs: Long = 6_000L,
) {
    fun phase(sinceStartMs: Long, pulsedMs: Long, interacting: Boolean): PulsePhase = when {
        pulsedMs >= pulseBudgetMs || sinceStartMs >= hardStopMs -> PulsePhase.SETTLED
        interacting && sinceStartMs >= settleAfterMs -> PulsePhase.SETTLED
        sinceStartMs < startDelayMs -> PulsePhase.WAIT
        interacting -> PulsePhase.PAUSE
        else -> PulsePhase.PULSE
    }
}

private const val PULSE_POLL_MS = 100L
private const val MAX_FRAME_STEP_MS = 100L

/**
 * Drives one shared pulse. Frames are requested only in [PulsePhase.PULSE] while [active];
 * waiting, paused and inactive states use timers, so the frame clock stays idle.
 * [timeline] null pulses continuously (whole skeleton rows and pages). Returns once settled.
 */
internal suspend fun runSkeletonPulse(
    timeline: DampedPulseTimeline?,
    setAlpha: (Float) -> Unit,
    nowMs: () -> Long,
    interacting: () -> Boolean,
    active: () -> Boolean,
    awaitActive: suspend () -> Unit,
) {
    val startMs = nowMs()
    var pulsedMs = 0L
    var lastFrameNs = -1L
    setAlpha(SkeletonPulseCurve.REST_ALPHA)
    while (true) {
        if (!active()) {
            lastFrameNs = -1L
            awaitActive()
            continue
        }
        val sinceStart = nowMs() - startMs
        when (timeline?.phase(sinceStart, pulsedMs, interacting()) ?: PulsePhase.PULSE) {
            PulsePhase.SETTLED -> {
                setAlpha(SkeletonPulseCurve.REST_ALPHA)
                return
            }
            PulsePhase.WAIT -> {
                lastFrameNs = -1L
                delay((checkNotNull(timeline).startDelayMs - sinceStart).coerceIn(1L, PULSE_POLL_MS))
            }
            PulsePhase.PAUSE -> {
                lastFrameNs = -1L
                delay(PULSE_POLL_MS)
            }
            PulsePhase.PULSE -> withFrameNanos { now ->
                if (lastFrameNs >= 0L) pulsedMs += ((now - lastFrameNs) / 1_000_000L).coerceIn(0L, MAX_FRAME_STEP_MS)
                lastFrameNs = now
                setAlpha(SkeletonPulseCurve.alpha(pulsedMs))
            }
        }
    }
}

/**
 * One pulse clock shared by every placeholder that acquired it; read [alpha] only at draw time
 * (graphicsLayer/drawBehind), so placeholders never recompose per frame. Runs only while the
 * app is resumed. A damped pulse that settled stays static until every user released it.
 */
internal class SharedPulse(private val timeline: DampedPulseTimeline?) {
    val alpha = mutableFloatStateOf(SkeletonPulseCurve.REST_ALPHA)
    private var users = 0
    private var job: Job? = null

    fun acquire() {
        if (users++ > 0) return
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        job = CoroutineScope(AndroidUiDispatcher.Main).launch {
            runSkeletonPulse(
                timeline = timeline,
                setAlpha = { alpha.floatValue = it },
                nowMs = android.os.SystemClock::uptimeMillis,
                interacting = { BrowseWorkBudget.recentlyInteracting() },
                active = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
                awaitActive = { lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) } },
            )
        }
    }

    fun release() {
        if (--users > 0) return
        users = 0
        job?.cancel()
        job = null
        alpha.floatValue = SkeletonPulseCurve.REST_ALPHA
    }
}

/** Whole skeleton rows and pages: pulses continuously while the app is resumed. */
internal val SharedSkeletonPulse = SharedPulse(timeline = null)

/** Single loading images: delayed, paused during input, settles after a few seconds. */
internal val SharedArtworkPulse = SharedPulse(DampedPulseTimeline())

/** Holds [pulse] while composed and returns its draw-time alpha. */
@Composable
internal fun rememberSharedPulse(pulse: SharedPulse): State<Float> {
    DisposableEffect(pulse) {
        pulse.acquire()
        onDispose { pulse.release() }
    }
    return pulse.alpha
}

/** Whole skeleton rows and pages: the shared continuous pulse, or null (static) under Sparsam. */
@Composable
internal fun rememberSkeletonPulseOrNull(): State<Float>? =
    if (LocalPerformanceProfile.current.reducedEffects) null else rememberSharedPulse(SharedSkeletonPulse)

/** Layer alpha for a placeholder: the pulse value, or the static rest alpha without a pulse. */
internal fun skeletonLayerAlpha(pulse: Float?): Float = (pulse ?: SkeletonPulseCurve.REST_ALPHA).coerceIn(0f, 1f)

/**
 * Applies [pulse] as layer alpha. The value is read only inside the graphicsLayer block, so a
 * pulse frame updates RenderNode properties without recomposition or re-recording; ModulateAlpha
 * multiplies each draw like a per-color alpha, without an offscreen buffer.
 */
internal fun Modifier.skeletonPulseAlpha(pulse: State<Float>?): Modifier = graphicsLayer {
    alpha = skeletonLayerAlpha(pulse?.value)
    compositingStrategy = CompositingStrategy.ModulateAlpha
}
