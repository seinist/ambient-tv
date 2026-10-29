package com.ambient.tv.ui.player

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Kept for the render watchdog's explicit expected-frame denominator. */
internal fun frameDropPercent(dropped: Long?, denominator: Double?): Double? =
    if (dropped == null || dropped < 0 || denominator == null || !denominator.isFinite() || denominator <= 0) null
    else (100.0 * dropped / denominator).takeIf { it.isFinite() }

internal fun formatFramePercent(value: Double?, locale: Locale = Locale.getDefault()): String = value?.let {
    NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(it) + " %"
} ?: "—"

internal data class FrameDropReading(
    val dropped: Long? = null,
    val rendered: Long? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** Expected pictures per media second, after rate-changing filters; never display Hz. */
    val fps: Double? = null,
    val estimated: Boolean = false,
    val realtimeMs: Long = 0,
    val speed: Double = 1.0,
    val generation: Int = 0,
) {
    val targetFps: Double?
        get() = fps?.takeIf { it.isFinite() && it > 0 && speed.isFinite() && speed > 0 }
            ?.times(speed)?.takeIf { it.isFinite() && it > 0 }
}

internal data class FrameDropSample(
    val actualFps: Double,
    val targetFps: Double?,
    val estimated: Boolean = false,
    /** Reported drops per real second; these do not define the nominal output cadence. */
    val droppedFps: Double? = null,
) {
    val deviationFps: Double? get() = targetFps?.let { actualFps - it }

    /** Share of the renderer's reported shown/dropped pictures, not an inferred FPS target. */
    val counterDropPercent: Double?
        get() {
            val drops = droppedFps?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
            if (!actualFps.isFinite() || actualFps < 0.0) return null
            val observed = actualFps + drops
            return if (observed.isFinite() && observed > 0.0) 100.0 * drops / observed else null
        }

    /** Known cadence measures output deficit; otherwise only reported counter loss is known. */
    val deficitPercent: Double?
        get() = targetFps?.let { (100.0 * (it - actualFps) / it).coerceIn(0.0, 100.0) }
            ?: counterDropPercent
}

private fun sameFrameTarget(first: Double?, second: Double?): Boolean =
    if (first == null || second == null) first == second
    else abs(first - second) <= maxOf(0.05, second * 0.005)

/**
 * Renderer output per wall-clock second, compared with nominal cadence only when it is known.
 * MPV exposes drops but no rendered counter here, so its estimate also needs a valid media clock.
 * True counter measurements ignore window-relative live positions. Reader generations mark
 * seeks, pauses and renderer changes; non-monotone counters invalidate either measurement.
 */
internal fun frameDropWindow(previous: FrameDropReading?, current: FrameDropReading): FrameDropSample? {
    if (previous == null || !previous.playing || !current.playing ||
        previous.estimated != current.estimated || previous.generation != current.generation) return null
    if (!previous.speed.isFinite() || previous.speed <= 0.0 ||
        !current.speed.isFinite() || current.speed <= 0.0 || abs(previous.speed - current.speed) > 0.001) return null
    val target = current.targetFps
    if (!sameFrameTarget(previous.targetFps, target)) return null
    val elapsed = current.realtimeMs - previous.realtimeMs
    if (elapsed !in 500L..2500L) return null
    if (current.dropped?.let { it < 0 } == true || previous.dropped?.let { it < 0 } == true) return null
    if (current.dropped != null && previous.dropped != null && current.dropped < previous.dropped) return null
    val seconds = elapsed / 1000.0
    val droppedFps = if (current.dropped != null && previous.dropped != null)
        (current.dropped - previous.dropped) / seconds else null
    val actual = if (current.estimated) {
        val expected = target ?: return null
        val drops = droppedFps ?: return null
        val progress = current.positionMs - previous.positionMs
        val expectedProgress = elapsed * current.speed
        // Estimates must not turn a seek, buffering or a stalled clock into playback time.
        if (progress <= 0 || abs(progress - expectedProgress) > maxOf(350.0, expectedProgress * 0.5)) return null
        (expected - drops).coerceAtLeast(0.0)
    } else {
        val rendered = current.rendered ?: return null
        val oldRendered = previous.rendered ?: return null
        if (oldRendered < 0 || rendered < oldRendered) return null
        (rendered - oldRendered) / seconds
    }
    return FrameDropSample(actual, target, current.estimated, droppedFps)
}

/** A missing/invalid sample clears the display instead of preserving stale green or red. */
internal fun smoothFrameDrop(previous: FrameDropSample?, sample: FrameDropSample?): FrameDropSample? = when {
    sample == null -> null
    previous == null || previous.estimated != sample.estimated || !sameFrameTarget(previous.targetFps, sample.targetFps) ||
        (previous.droppedFps == null) != (sample.droppedFps == null) -> sample
    else -> sample.copy(
        actualFps = previous.actualFps + 0.35 * (sample.actualFps - previous.actualFps),
        droppedFps = if (previous.droppedFps != null && sample.droppedFps != null)
            previous.droppedFps + 0.35 * (sample.droppedFps - previous.droppedFps) else null,
    )
}

internal const val FRAME_DROP_HISTORY_WINDOW_MS = 60_000L
internal const val FRAME_DROP_HISTORY_MAX_PERCENT = 20.0
private const val FRAME_DROP_HISTORY_CAPACITY = 61

/** An interval ends at [realtimeMs]; null is an explicit gap, never a zero-drop measurement. */
internal data class FrameDropHistoryPoint(val realtimeMs: Long, val percent: Double?, val estimated: Boolean)

/**
 * The existing one-second reader supplies both the compact display and this bounded history.
 * Keep raw counter ratios, before smoothing. Invalid windows (pause/seek/counter generation)
 * stay as gaps; a new handle owns a new history, and a non-monotone clock starts it over.
 */
internal class FrameDropHistory {
    private val buffer = ArrayDeque<FrameDropHistoryPoint>(FRAME_DROP_HISTORY_CAPACITY)
    private var previous: FrameDropReading? = null
    val points: List<FrameDropHistoryPoint> get() = buffer.toList()

    fun record(current: FrameDropReading): FrameDropSample? {
        if (previous?.let { current.realtimeMs <= it.realtimeMs } == true) {
            buffer.clear()
            previous = null
        }
        val sample = frameDropWindow(previous, current)
        val percent = sample?.counterDropPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        buffer.addLast(FrameDropHistoryPoint(current.realtimeMs, percent, current.estimated))
        while (buffer.size > FRAME_DROP_HISTORY_CAPACITY ||
            buffer.first().realtimeMs < current.realtimeMs - FRAME_DROP_HISTORY_WINDOW_MS) buffer.removeFirst()
        previous = current
        return sample
    }
}

/** Real output can be zero; an unavailable measurement must remain distinguishable from it. */
internal fun formatFrameOutputFps(value: Double?, locale: Locale = Locale.getDefault()): String =
    value?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            isGroupingUsed = false
        }.format(if (it == 0.0) 0.0 else it)
    } ?: "—"

internal fun formatFrameFpsDeviation(value: Double?, locale: Locale = Locale.getDefault()): String {
    if (value == null || !value.isFinite()) return "—"
    // Avoid showing a negative zero from fractional frame rates and polling jitter.
    val magnitude = if (abs(value) < 0.05) 0.0 else abs(value)
    val sign = when { magnitude == 0.0 -> ""; value < 0 -> "−"; else -> "+" }
    return sign + NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }.format(magnitude)
}

internal fun formatFrameTargetFps(value: Double?, locale: Locale = Locale.getDefault()): String =
    value?.takeIf { it.isFinite() && it > 0 }?.let {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3; isGroupingUsed = false }.format(it)
    } ?: "—"

/** Preserve the bar's visual scale: half full/red at 10% below target, empty at 20%. */
internal const val FRAME_DROP_WATCHDOG_PERCENT = 10.0

internal fun frameDropBarFill(deficitPercent: Double?): Float =
    if (deficitPercent == null) 1f else (1.0 - deficitPercent / (2 * FRAME_DROP_WATCHDOG_PERCENT)).coerceIn(0.0, 1.0).toFloat()
