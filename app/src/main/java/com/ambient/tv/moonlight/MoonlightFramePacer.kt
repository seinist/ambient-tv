package com.ambient.tv.moonlight

import java.util.concurrent.atomic.AtomicLongArray

/** Native queue has a 3..15 frame bound; detect sustained pressure before overflow. */
internal fun moonlightVideoBacklogThreshold(fps: Int): Int =
    ((fps / 10).coerceIn(3, 15) - 1).coerceAtLeast(2)

/** Bound decoder input waits by frame rate while leaving direct-submit network ingress short. */
internal fun moonlightDecoderInputTimeoutUs(mode: String, fps: Int, direct: Boolean, config: Boolean): Long {
    if (direct) return 500L
    if (config || mode == "smooth") return 3_000L
    val intervalUs = 1_000_000L / fps.coerceIn(1, 240)
    return if (mode == "latency") (intervalUs / 8).coerceIn(750L, 3_000L)
    else (intervalUs / 4).coerceIn(1_000L, 3_000L)
}

/** Bounded presentation timestamps; no second unbounded decoder-output queue. */
internal class MoonlightFramePacer(private val mode: String, fps: Int) {
    private val interval = 1_000_000_000L / fps.coerceIn(1, 240)
    private val window = when (mode) { "smooth" -> 2L; "balanced" -> 1L; else -> 0L }
    private var lastTarget = 0L
    fun shouldDrain(ptsUs: Long, nowNs: Long): Boolean =
        window == 0L || nowNs / 1000L - ptsUs > window * interval / 1000L

    fun reset() { lastTarget = 0L }

    fun presentationTime(nowNs: Long): Long {
        val target = if (window == 0L || lastTarget == 0L || lastTarget <= nowNs - interval * 2)
            nowNs else (lastTarget + interval).coerceIn(nowNs, nowNs + interval * window)
        lastTarget = target
        return target
    }
}

/** Session-local bounded histogram; returns the upper bucket bound, not an exact percentile. */
internal class MoonlightLatencyHistogram {
    private val limits = intArrayOf(1, 2, 4, 8, 12, 16, 25, 33, 50, 75, 100, 250, 500, 1000, 2000, 5000, 10000)
    private val counts = AtomicLongArray(limits.size)
    fun recordUs(us: Long) {
        if (us < 0L || us > 10_000_000L) return
        // Binary search avoids scanning every bucket on the decoder hot path.
        var low = 0
        var high = limits.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (us <= limits[mid] * 1000L) high = mid else low = mid + 1
        }
        counts.incrementAndGet(low)
    }
    fun p95Ms(): Int? {
        val snapshot = LongArray(limits.size) { counts.get(it) }
        val total = snapshot.sum()
        if (total == 0L) return null
        val threshold = total - total / 20L
        var cumulative = 0L
        for (i in snapshot.indices) {
            cumulative += snapshot[i]
            if (cumulative >= threshold) return limits[i]
        }
        return limits.last()
    }
}
