package com.ambient.tv.ui.player

/**
 * Small allocation-free playback-thread history. Presentation times are period-relative
 * (MediaCodecVideoRenderer subtracts the output stream offset). Only a single-period,
 * zero-offset VOD timeline may use these times as player seek positions.
 *
 * A pending decoder buffer is evidence of a possible next picture, not proof it will be
 * presented. Effects/tunneled paths and unknown neighbors still use an approximate seek.
 */
internal class Media3FrameTimeline(private val capacity: Int = 120) {
    private val frames = LongArray(capacity)
    private var count = 0
    private var cursor = 0
    private var lastPresented = -1L
    private var pending = -1L
    private var generation = 0L
    private val outputGeneration = ThreadLocal<Long>()
    // UI seek invalidation precedes the playback-thread reset. Reject old
    // outputs in that interval instead of assigning them a new generation.
    private var waitingForReset = false
    private var rendererResetCount = 0L

    init { require(capacity > 0) }

    private fun remember(ptsUs: Long) {
        if (ptsUs < 0L) return
        for (i in 0 until count) if (frames[i] == ptsUs) return
        frames[cursor] = ptsUs
        cursor = (cursor + 1) % capacity
        count = minOf(count + 1, capacity)
    }

    /** A real, non-decode-only output timestamp is useful even if rendering
     * dropped it. It is not proof that the picture was displayed. */
    @Synchronized fun generation(): Long = generation
    @Synchronized fun beginOutput(): Long = (if (waitingForReset) -1L else generation).also { outputGeneration.set(it) }
    fun endOutput() { outputGeneration.remove() }
    fun presentedFromOutput(ptsUs: Long) { outputGeneration.get()?.let { presented(ptsUs, it) } }
    @Synchronized fun decoded(ptsUs: Long, token: Long = generation) {
        if (token == generation) remember(ptsUs)
    }

    @Synchronized fun presented(ptsUs: Long, token: Long = generation) {
        if (token != generation || ptsUs < 0L) return
        remember(ptsUs)
        lastPresented = ptsUs
        if (pending == ptsUs) pending = -1L
    }

    @Synchronized fun pending(ptsUs: Long, token: Long = generation) {
        if (token == generation) pending = ptsUs.coerceAtLeast(-1L)
    }

    /** Frame stepping retains verified history but invalidates in-flight output. */
    @Synchronized fun invalidatePending() { generation++; pending = -1L }

    @Synchronized fun expectRendererReset(retainHistory: Boolean): Long {
        if (retainHistory) invalidatePending() else clear()
        waitingForReset = true
        return rendererResetCount
    }

    @Synchronized fun cancelExpectedReset(ticket: Long) {
        if (rendererResetCount == ticket) waitingForReset = false
    }

    @Synchronized fun rendererReset(retainHistory: Boolean = false) {
        if (waitingForReset && retainHistory) invalidatePending() else clear()
        waitingForReset = false
        rendererResetCount++
    }

    @Synchronized fun clear() {
        generation++
        count = 0
        cursor = 0
        lastPresented = -1L
        pending = -1L
    }

    /** Prefer the actual displayed frame to the audio clock; a stale frame cannot anchor a seek. */
    @Synchronized fun anchor(positionUs: Long): Long =
        lastPresented.takeIf { it >= 0 && kotlin.math.abs(it - positionUs) <= 250_000L } ?: positionUs

    /** Known adjacent presentation time, or null when the history has no trustworthy neighbor. */
    @Synchronized fun neighbor(anchorUs: Long, forward: Boolean): Long? {
        var nearest = if (forward) Long.MAX_VALUE else -1L
        for (i in 0 until count) {
            val value = frames[i]
            if (forward && value / 1000L > anchorUs / 1000L && value < nearest) nearest = value
            if (!forward && value / 1000L < anchorUs / 1000L && value > nearest) nearest = value
        }
        val next = pending
        if (forward && next >= 0L && next / 1000L > anchorUs / 1000L && next < nearest) nearest = next
        // A distant timestamp can belong to an earlier seek or skipped sequence.
        return nearest.takeIf { it >= 0L && it != Long.MAX_VALUE && kotlin.math.abs(it - anchorUs) <= 2_000_000L }
    }
}

/** A frame step is not an explicit logical EOF seek. Keep its target inside the picture range. */
internal data class Media3FrameStepPlan(val targetMs: Long, val cursorUs: Long)

internal fun media3FrameStepPlan(
    anchorUs: Long,
    candidateUs: Long?,
    durationMs: Long,
    forward: Boolean,
    fps: Float,
    live: Boolean,
): Media3FrameStepPlan {
    val anchor = anchorUs.coerceAtLeast(0L)
    val periodUs = if (fps.isFinite() && fps > 1f)
        (1_000_000.0 / fps).toLong().coerceAtLeast(1000L) else 40_000L
    val known = candidateUs?.takeIf {
        it >= 0L && if (forward) it / 1000L > anchor / 1000L else it / 1000L < anchor / 1000L
    }
    val desired = known ?: if (forward) anchor + periodUs else anchor - periodUs
    val maxMs = if (durationMs in 1 until Long.MAX_VALUE / 1000L)
        (durationMs - if (live) 0L else 1L).coerceAtLeast(0L) else Long.MAX_VALUE / 1000L - 1L
    val target = (desired.coerceAtLeast(0L) / 1000L).coerceAtMost(maxMs)
    // Keep fractional cadence across repeated fallback steps, but never remember an
    // unreachable timestamp outside the clamped seek range.
    val cursor = if (desired >= 0L && desired / 1000L == target) desired else target * 1000L
    return Media3FrameStepPlan(target, cursor)
}
