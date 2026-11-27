package com.ambient.tv.ui.player.backend

internal enum class VlcVulkanOutputVerdict { CONFIRMED, STALLED }

/** Startup evidence is scoped to one media/window; cumulative counters alone cannot prove
 * that a recreated surface is actually presenting. Ordinary output stops querying after confirmation;
 * experimental output requires continuous evidence of presentation progress. */
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
    private var unverifiedSince: Long? = null

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
        unverifiedSince = null
    }

    fun shouldSample(now: Long): Boolean =
        !finished && (sampledAt?.let { now - it >= intervalMs } ?: true)

    fun observe(now: Long, decoded: Int?, displayed: Int?, continuous: Boolean = false): VlcVulkanOutputVerdict? {
        if (!shouldSample(now)) return null
        sampledAt = now
        // Only observed presentation progress (or an explicit suspension)
        // renews the experimental deadline. Intermittent missing statistics
        // and counter resets must not indefinitely mask a black screen.
        val unverifiedAt = if (continuous) unverifiedSince ?: now.also { unverifiedSince = it }
            else null
        if (decoded == null || displayed == null || decoded < 0 || displayed < 0) {
            if (!continuous) {
                baselineAt = null
                baselineDecoded = null
                baselineDisplayed = null
            }
            if (unverifiedAt != null && now - unverifiedAt >= missingCountersMs) {
                finished = true
                return VlcVulkanOutputVerdict.STALLED
            }
            return null
        }
        val firstDecoded = baselineDecoded
        val firstDisplayed = baselineDisplayed
        val firstAt = baselineAt
        if (firstDecoded == null || firstDisplayed == null || firstAt == null ||
            decoded < firstDecoded || displayed < firstDisplayed) {
            baselineAt = now
            baselineDecoded = decoded
            baselineDisplayed = displayed
            if (unverifiedAt != null && now - unverifiedAt >= starvationMs) {
                finished = true
                return VlcVulkanOutputVerdict.STALLED
            }
            return null
        }
        if (displayed > firstDisplayed) {
            if (!continuous) finished = true
            // The experimental path can lose support after successful startup
            // (format change, seek, device loss). Keep a moving progress baseline.
            baselineAt = now
            baselineDecoded = decoded
            baselineDisplayed = displayed
            if (continuous) unverifiedSince = now
            if (confirmed) return null
            confirmed = true
            return VlcVulkanOutputVerdict.CONFIRMED
        }
        val stalled = now - firstAt >= stallMs && decoded > firstDecoded
        val starved = unverifiedAt != null && now - unverifiedAt >= starvationMs
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
