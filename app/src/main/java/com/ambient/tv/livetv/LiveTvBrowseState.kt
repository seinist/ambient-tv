package com.ambient.tv.livetv

/** In-memory browse snapshot, owned by one configured repository (never persisted to disk). */
class LiveTvBrowseState(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var dashboard: LiveTvDashboard? = null
        private set
    var capabilities = LiveTvCapabilities()
        private set
    private var loadedAt: Long? = null
    private var recoveryRevision = 0L
    var savedView: Boolean = false
        private set
    internal var preparedListing: com.ambient.tv.ui.tvheadend.CompletedLiveTvListing? = null

    fun observeRecovery(revision: Long): Boolean {
        if (revision <= recoveryRevision) return false
        recoveryRevision = revision
        loadedAt = null
        return true
    }
    // Home and the guide can know the current broadcast before a player exists.
    @Volatile private var homeNowEvents: Map<String, LiveTvProgramme> = emptyMap()

    fun rememberNowEvents(events: List<LiveTvProgramme>) {
        homeNowEvents = events.associateBy { it.channelUuid }
    }

    internal fun programmePair(channelId: String, now: Long): Pair<LiveTvProgramme?, LiveTvProgramme?> {
        val events = dashboard?.events.orEmpty().asSequence().filter { it.channelUuid == channelId }
        val home = homeNowEvents[channelId]?.takeIf { it.isLive(now) }
        val current = home ?: events.firstOrNull { it.isLive(now) }
        val next = events.filter { it.startEpochSeconds >= (current?.stopEpochSeconds ?: now) }
            .minByOrNull { it.startEpochSeconds }
        return current to next
    }

    private val positions = mutableMapOf<String, Position>()

    data class Position(val itemId: String, val offset: Int)

    fun needsRefresh(): Boolean = loadedAt?.let { now() - it >= 45_000 } ?: true

    fun update(dashboard: LiveTvDashboard, capabilities: LiveTvCapabilities, savedView: Boolean = false) {
        this.dashboard = dashboard
        this.capabilities = capabilities
        if (!savedView && dashboard.warnings.isEmpty() && dashboard.events.isNotEmpty()) {
            // Only replace Home evidence for channels actually covered by this dashboard.
            val covered = dashboard.events.asSequence().map { it.channelUuid }.toHashSet()
            homeNowEvents = homeNowEvents.filterKeys { it !in covered }
        }
        this.savedView = savedView
        loadedAt = if (!savedView && dashboard.warnings.isEmpty()) now() else null
    }

    /** Optional channel artwork/service metadata must not change the dashboard's access proof,
     * freshness, recordings or focus. Retained destinations share this immutable projection. */
    internal fun updateChannelMetadata(enrich: (List<LiveTvChannel>) -> List<LiveTvChannel>) {
        val old = dashboard ?: return
        val channels = enrich(old.channels)
        if (channels !== old.channels) dashboard = old.copy(channels = channels)
    }

    /** A confirmed mutation must survive navigation even if its follow-up request fails. */
    fun updateAfterMutation(dashboard: LiveTvDashboard) {
        this.dashboard = dashboard
        loadedAt = null
    }

    fun position(key: String): Position? = positions[key]
    fun savePosition(key: String, itemId: String, offset: Int) {
        positions[key] = Position(itemId, offset)
    }

    /** A new app session starts at the beginning while keeping the cached dashboard fresh. */
    fun clearPositions() = positions.clear()
}
