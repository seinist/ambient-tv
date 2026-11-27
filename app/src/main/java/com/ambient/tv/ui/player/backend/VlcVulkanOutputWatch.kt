package com.ambient.tv.ui.player.backend

internal enum class VlcVulkanOutputVerdict { CONFIRMED, STALLED }

/** Startup evidence is scoped to one media/window; cumulative counters alone cannot prove
 * that a recreated surface is actually presenting. Once confirmed, stop querying statistics. */
internal class VlcVulkanOutputWatch(
    private val intervalMs: Long = 1_000L,
    private val stallMs: Long = 8_000L,
    private val starvationMs: Long = 15_000L,
    private val missingCountersMs: Long = 20_000L,
) {
    private var finished = false
    private var confirmed = false
    private var sampledAt: Long? = null
    private var baselineAt: Long? = null
    private var baselineDecoded: Int? = null
    private var baselineDisplayed: Int? = null
    private var missingSince: Long? = null

    fun reset() {
        finished = false
        confirmed = false
        suspendObservation()
    }

    /** Do not count paused, buffering or otherwise ineligible time as a rendering stall. */
    fun suspendObservation() {
        sampledAt = null
        baselineAt = null
        baselineDecoded = null
        baselineDisplayed = null
        missingSince = null
    }

    fun shouldSample(now: Long): Boolean =
        !finished && (sampledAt?.let { now - it >= intervalMs } ?: true)

    fun observe(now: Long, decoded: Int?, displayed: Int?, continuous: Boolean = false): VlcVulkanOutputVerdict? {
        if (!shouldSample(now)) return null
        sampledAt = now
        if (decoded == null || displayed == null || decoded < 0 || displayed < 0) {
            baselineAt = null
            baselineDecoded = null
            baselineDisplayed = null
            // No presentation statistics means this experimental path
            // cannot be verified. Fall back once after a longer interval,
            // but only during eligible, active video playback.
            if (continuous) {
                val since = missingSince ?: now.also { missingSince = it }
                if (now - since >= missingCountersMs) {
                    finished = true
                    return VlcVulkanOutputVerdict.STALLED
                }
            }
            return null
        }
        missingSince = null
        val firstDecoded = baselineDecoded
        val firstDisplayed = baselineDisplayed
        val firstAt = baselineAt
        if (firstDecoded == null || firstDisplayed == null || firstAt == null ||
            decoded < firstDecoded || displayed < firstDisplayed) {
            baselineAt = now
            baselineDecoded = decoded
            baselineDisplayed = displayed
            return null
        }
        if (displayed > firstDisplayed) {
            if (!continuous) finished = true
            // The experimental path can lose support after successful startup
            // (format change, seek, device loss). Keep a moving progress baseline.
            baselineAt = now
            baselineDecoded = decoded
            baselineDisplayed = displayed
            if (confirmed) return null
            confirmed = true
            return VlcVulkanOutputVerdict.CONFIRMED
        }
        val stalled = now - firstAt >= stallMs && decoded > firstDecoded
        val starved = continuous && now - firstAt >= starvationMs
        if (stalled || starved) {
            finished = true
            return VlcVulkanOutputVerdict.STALLED
        }
        return null
    }
}


/** Secondary watchdog for startup/buffering/seek states in which the ordinary
 * presentation observer is suspended. Network progress extends the deadline;
 * no progress eventually disables only the experimental decoder route.
 * All timestamps are monotonic elapsedRealtime values supplied by the owner. */
internal class VlcAhbOpeningWatch(private val idleLimitMs: Long = 30_000L) {
    private var since: Long? = null
    private var marker: Long? = null

    fun reset() {
        since = null
        marker = null
    }

    fun observe(now: Long, progress: Long, active: Boolean): Boolean {
        if (!active) {
            reset()
            return false
        }
        val start = since
        if (start == null || marker != progress || now < start) {
            since = now
            marker = progress
            return false
        }
        return now - start >= idleLimitMs
    }
}
