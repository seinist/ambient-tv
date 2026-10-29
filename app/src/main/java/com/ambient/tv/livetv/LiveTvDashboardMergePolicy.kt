package com.ambient.tv.livetv

internal enum class LiveTvDashboardPart(val warningLabel: String) {
    EPG("EPG"), RECORDINGS("Recordings"), TIMERS("Timers"),
    FAILED_RECORDINGS("Failed recordings"), SERIES_TIMERS("Series timers"),
}

/** A failure in one optional endpoint must not mask confirmed removals from another endpoint. */
internal class LiveTvDashboardMergePolicy(warnings: List<String>) {
    private val failed = warnings.mapTo(HashSet()) { it.substringBefore(':') }

    fun <T> merge(part: LiveTvDashboardPart, fresh: List<T>, previous: List<T>): List<T> =
        if (part.warningLabel in failed && fresh.isEmpty()) previous else fresh
}
