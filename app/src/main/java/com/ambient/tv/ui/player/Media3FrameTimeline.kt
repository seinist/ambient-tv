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

    @Synchronized fun presented(ptsUs: Long) {
        if (ptsUs < 0L) return
        if (lastPresented != ptsUs) {
            frames[cursor] = ptsUs
            cursor = (cursor + 1) % capacity
            count = minOf(count + 1, capacity)
        }
        lastPresented = ptsUs
        if (pending == ptsUs) pending = -1L
    }

    @Synchronized fun pending(ptsUs: Long?) { pending = ptsUs?.takeIf { it >= 0L } ?: -1L }

    @Synchronized fun clear() {
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
            if (forward && value > anchorUs && value < nearest) nearest = value
            if (!forward && value < anchorUs && value > nearest) nearest = value
        }
        if (forward && pending > anchorUs && pending < nearest) nearest = pending
        // A distant timestamp can belong to an earlier seek or skipped sequence.
        return nearest.takeIf { it >= 0L && it != Long.MAX_VALUE && kotlin.math.abs(it - anchorUs) <= 2_000_000L }
    }
}
