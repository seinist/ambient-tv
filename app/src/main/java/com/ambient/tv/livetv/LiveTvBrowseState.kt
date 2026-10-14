package com.ambient.tv.livetv

/** In-memory browse snapshot, owned by one configured repository (never persisted to disk). */
class LiveTvBrowseState(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var dashboard: LiveTvDashboard? = null
        private set
    var capabilities = LiveTvCapabilities()
        private set
    private var loadedAt: Long? = null
    private val positions = mutableMapOf<String, Position>()

    data class Position(val itemId: String, val offset: Int)

    fun needsRefresh(): Boolean = loadedAt?.let { now() - it >= 45_000 } ?: true

    fun update(dashboard: LiveTvDashboard, capabilities: LiveTvCapabilities) {
        this.dashboard = dashboard
        this.capabilities = capabilities
        loadedAt = if (dashboard.warnings.isEmpty()) now() else null
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
}
