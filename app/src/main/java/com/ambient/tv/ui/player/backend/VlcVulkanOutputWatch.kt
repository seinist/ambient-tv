package com.ambient.tv.ui.player.backend

internal enum class VlcVulkanOutputVerdict { CONFIRMED, STALLED }

/** Startup evidence is scoped to one media/window; cumulative counters alone cannot prove
 * that a recreated surface is actually presenting. Once confirmed, stop querying statistics. */
internal class VlcVulkanOutputWatch(
    private val intervalMs: Long = 1_000L,
    private val stallMs: Long = 8_000L,
) {
    private var finished = false
    private var sampledAt: Long? = null
    private var baselineAt: Long? = null
    private var baselineDecoded: Int? = null
    private var baselineDisplayed: Int? = null

    fun reset() {
        finished = false
        suspendObservation()
    }

    /** Do not count paused, buffering or otherwise ineligible time as a rendering stall. */
    fun suspendObservation() {
        sampledAt = null
        baselineAt = null
        baselineDecoded = null
        baselineDisplayed = null
    }

    fun shouldSample(now: Long): Boolean =
        !finished && (sampledAt?.let { now - it >= intervalMs } ?: true)

    fun observe(now: Long, decoded: Int?, displayed: Int?): VlcVulkanOutputVerdict? {
        if (!shouldSample(now)) return null
        sampledAt = now
        if (decoded == null || displayed == null || decoded < 0 || displayed < 0) {
            baselineAt = null
            baselineDecoded = null
            baselineDisplayed = null
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
            return null
        }
        if (displayed > firstDisplayed) {
            finished = true
            return VlcVulkanOutputVerdict.CONFIRMED
        }
        if (now - firstAt >= stallMs && decoded > firstDecoded) {
            finished = true
            return VlcVulkanOutputVerdict.STALLED
        }
        return null
    }
}
