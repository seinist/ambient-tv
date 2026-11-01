package com.ambient.tv.youtube

/**
 * Tiles shown from the cache stay exactly as they are for the rest of a visit: a background
 * refresh only updates the cache, and the next opening of the page shows the new videos.
 * An empty screen, an explicit reload and an account or language change (a new cache
 * revision) take fresh rows immediately, as before.
 */
internal class YouTubeVisitPin {
    private var key: String? = null
    private var revision: Long = Long.MIN_VALUE

    /** True while [key]'s visible rows belong to this visit of the same cache revision. */
    fun holds(key: String, revision: Long): Boolean = this.key == key && this.revision == revision

    /**
     * Called when a load has put [visible] on screen for [key]. Returns whether the refresh
     * that follows must leave the visible rows untouched.
     */
    fun begin(key: String, revision: Long, visible: List<YouTubeRow>, explicit: Boolean): Boolean {
        if (explicit || visible.isEmpty()) {
            release()
            return false
        }
        this.key = key
        this.revision = revision
        return true
    }

    fun release() {
        key = null
        revision = Long.MIN_VALUE
    }
}

/** A completed background refresh is stored in the cache; a pinned visit keeps its rows. */
internal fun youtubeRowsAfterRefresh(
    visible: List<YouTubeRow>,
    stored: List<YouTubeRow>,
    pinned: Boolean,
): List<YouTubeRow> = if (pinned && visible.isNotEmpty()) visible else stored

/**
 * A row continuation page extends the rows the user is looking at. A pinned visit appends it to
 * its own visible rows, so pagination keeps working without pulling in the deferred refresh.
 */
internal fun youtubeRowsAfterContinuation(
    visible: List<YouTubeRow>,
    cached: List<YouTubeRow>?,
    rowId: String,
    items: List<YouTubeItem>,
    pinned: Boolean,
): List<YouTubeRow> = if (pinned) appendYouTubeRowItems(visible, rowId, items) else cached ?: visible

/** How a Home load treats the YouTube rows that are already on screen. */
internal enum class HomeYouTubeLoadMode {
    /** Home is opened: show the newest cache, then keep it still. */
    OPEN,
    /** Timer, reconnect or retry while Home stays open: visible rows stay as they are. */
    BACKGROUND,
    /** Manual reload or account/language change: fresh rows apply as soon as they arrive. */
    EXPLICIT,
}

/** The Home screen's YouTube rows follow the same rule as the SmartTube page. */
internal class HomeYouTubePins {
    private val keys = mutableSetOf<String>()

    fun startLoad(mode: HomeYouTubeLoadMode) {
        if (mode != HomeYouTubeLoadMode.BACKGROUND) keys.clear()
    }

    /** Whether the cached row for [key] may be put on screen at the start of this load. */
    fun mayShowCached(key: String, mode: HomeYouTubeLoadMode, visible: Boolean): Boolean =
        !(mode == HomeYouTubeLoadMode.BACKGROUND && visible && key in keys)

    fun cachedShown(key: String, mode: HomeYouTubeLoadMode) {
        if (mode != HomeYouTubeLoadMode.EXPLICIT) keys += key
    }

    /** Whether a finished refresh for [key] may replace what is on screen. */
    fun mayApplyFresh(key: String, visible: Boolean): Boolean = !(visible && key in keys)

    fun clear() = keys.clear()
}
