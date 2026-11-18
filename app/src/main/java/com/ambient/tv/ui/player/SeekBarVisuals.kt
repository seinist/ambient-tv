package com.ambient.tv.ui.player

/** Brief UI-only hold while a renderer still reports its pre-seek position. */
internal const val SEEK_VISUAL_ACK_TIMEOUT_MS = 2_500L
internal const val SEEK_VISUAL_ACK_TOLERANCE_MS = 1_000L

/** Does not modify the engine's playback-seek coordinator or committed timeline clock. */
internal fun seekBarVisualPosition(
    publishedPositionMs: Long,
    durationMs: Long,
    pointerTargetMs: Long? = null,
    keyboardTargetMs: Long? = null,
    awaitingTargetMs: Long? = null,
): Long = (pointerTargetMs ?: keyboardTargetMs ?: awaitingTargetMs ?: publishedPositionMs)
    .coerceIn(0L, durationMs.coerceAtLeast(0L))

internal fun seekBarFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

/** Current playback position during a real seek, not a permanently displayed marker.
 * A separate preview target can move the progress fill without replacing this position.
 * Focus, selection and expanded/compact OSD mode alone must never enable the line.
 */
internal fun seekBarPlayheadFraction(positionMs: Long, durationMs: Long, seeking: Boolean): Float? =
    if (seeking && durationMs > 0L) seekBarFraction(positionMs, durationMs) else null

/** Fade just the seek playhead's opacity; low-performance mode uses shorter tweens.
 * The OSD layout, track, progress brush, input handling and preview timing do not move.
 */
internal fun seekBarPlayheadFadeDurationMs(showing: Boolean, reducedEffects: Boolean): Int =
    when {
        reducedEffects && showing -> 90
        reducedEffects -> 120
        showing -> 150
        else -> 220
    }

internal fun seekBarHasDistinctTarget(publishedMs: Long, targetMs: Long, seeking: Boolean): Boolean =
    seeking && kotlin.math.abs(publishedMs - targetMs) >= 1_000L

/** The thumb scales inside the existing drawing layer; the surrounding layout is fixed. */
internal fun seekBarThumbScale(scrubbing: Boolean): Float = if (scrubbing) 1.18f else 1f
