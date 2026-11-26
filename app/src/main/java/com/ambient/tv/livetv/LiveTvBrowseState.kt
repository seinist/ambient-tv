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
    private val positions = mutableMapOf<String, Position>()

    data class Position(val itemId: String, val offset: Int)

    fun needsRefresh(): Boolean = loadedAt?.let { now() - it >= 45_000 } ?: true

    fun update(dashboard: LiveTvDashboard, capabilities: LiveTvCapabilities, savedView: Boolean = false) {
        this.dashboard = dashboard
        this.capabilities = capabilities
        this.savedView = savedView
        loadedAt = if (!savedView && dashboard.warnings.isEmpty()) now() else null
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
