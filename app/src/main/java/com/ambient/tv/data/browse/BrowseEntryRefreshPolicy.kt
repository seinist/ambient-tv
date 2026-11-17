package com.ambient.tv.data.browse

/**
 * The first visit of a source in a process trusts its persisted repository TTL. Otherwise
 * BrowseReconnects expired it just before the first read, defeating cache-first cold starts.
 * Network recovery and explicit Refresh always bypass this entry policy.
 */
internal class BrowseEntryRefreshPolicy(
    private val minIntervalMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastAccepted = mutableMapOf<String, Long>()

    @Synchronized fun sourcesToExpire(sources: Set<String>): Set<String> {
        val now = clock()
        return sources.filterTo(linkedSetOf()) { source ->
            val previous = lastAccepted[source]
            if (previous == null) {
                lastAccepted[source] = now
                false
            } else if (now - previous >= minIntervalMs || now < previous) {
                lastAccepted[source] = now
                true
            } else false
        }
    }
}
