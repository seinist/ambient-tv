package com.ambient.tv.ui.tvheadend

/**
 * A dashboard can be a cached or progressive CHANNELS-only snapshot. Empty EPG, DVR, timers and
 * HbbTV entries must not be presented as confirmed emptiness before this entry's read settles.
 */
internal fun TvheadendUiState.tabHasContent(): Boolean {
    val current = dashboard ?: return false
    return when (tab) {
        TvheadendTab.LIVE, TvheadendTab.GUIDE -> current.channels.isNotEmpty()
        TvheadendTab.RECORDINGS -> current.recordings.isNotEmpty() || current.failedRecordings.isNotEmpty()
        TvheadendTab.TIMERS -> current.upcoming.isNotEmpty() || current.autoRecs.isNotEmpty()
        TvheadendTab.HBBTV -> tvheadendHbbtvEntries(current.channels).isNotEmpty()
    }
}

internal fun TvheadendUiState.awaitingInitialContent(): Boolean =
    !tabHasContent() && (loading || refreshing || !initialDashboardSettled)

internal fun TvheadendUiState.emptyContentHasError(): Boolean =
    !tabHasContent() && error != null && !loading && !refreshing
