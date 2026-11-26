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
    !tabHasContent() && (loading || refreshing || !initialDashboardSettled || (tab == TvheadendTab.HBBTV && hbbtvPending))

internal fun TvheadendUiState.emptyContentHasError(): Boolean =
    !tabHasContent() && error != null && !loading && !refreshing

/** An offline/cached empty tab is unknown, never proof that there are no recordings or timers. */
internal fun TvheadendUiState.emptyContentUnconfirmed(): Boolean =
    !tabHasContent() && !loading && !refreshing &&
        (connectionRequired || dashboard?.warnings?.isNotEmpty() == true)

internal val TvheadendUiState.connectionRequired: Boolean get() = offline || savedView || serviceUnconfirmed

/** A failed write is not a confirmed deletion, nor permission to replay that write later. */
internal fun TvheadendUiState.afterUnconfirmedRecordingAction(feedback: com.ambient.tv.notifications.OperationFeedback): TvheadendUiState =
    copy(savedView = true, actionInProgress = false, actionFeedback = feedback)
