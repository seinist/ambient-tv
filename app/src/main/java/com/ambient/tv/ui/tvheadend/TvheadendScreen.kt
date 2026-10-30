package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.guideChannels
import com.ambient.tv.livetv.LiveTvArt
import com.ambient.tv.livetv.liveTvProgrammeArt
import com.ambient.tv.livetv.liveTvRecordingArt
import com.ambient.tv.livetv.liveTvSeriesArt

import com.ambient.tv.notifications.OperationFeedback
import com.ambient.tv.ui.common.OperationFeedbackScope
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.FeedbackLayout

import androidx.activity.compose.BackHandler
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.DeviceActionRow
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Info
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.ambient.tv.ui.common.TvLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.ambient.tv.ui.common.browseFocusTarget
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.tvheadend.domain.TvhAutoRec
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhDvrEntry
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.ArtworkImage
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.MarqueeTitle
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PageBackground
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.rememberPendingAction
import com.ambient.tv.ui.common.TvChip
import androidx.compose.foundation.lazy.rememberLazyListState
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.common.mediaFocusMotion
import com.ambient.tv.ui.common.handheldSwipeTabs
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val LocalTvhPlaybackSource = compositionLocalOf { "" }

/** A delete or cancel waiting for its confirmation. */
private sealed interface PendingDelete {
    data class Recording(val entry: TvhDvrEntry) : PendingDelete
    data class Timer(val entry: TvhDvrEntry) : PendingDelete
    data class Series(val entry: TvhAutoRec) : PendingDelete
}

@Composable
private fun livePlaybackIndicator(kind: String, id: String): com.ambient.tv.companion.PlaybackIndicator? {
    val scope = com.ambient.tv.companion.LocalPlaybackBrowseSources.current.live[LocalTvhPlaybackSource.current] ?: return null
    return com.ambient.tv.companion.playbackIndicator(com.ambient.tv.companion.CompanionMedia("live:$kind", id, scope))
}

internal data class TvhFocusTarget(val contentId: String?, val rowId: String? = null)

@Composable
fun TvheadendScreen(
    initialTab: TvheadendTab = TvheadendTab.LIVE,
    onOpenSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
    /** Tab chips update the nav route so the sidebar stays in sync. */
    onSelectTab: ((TvheadendTab) -> Unit)? = null,
    onWatchChannel: (String) -> Unit,
    onPlayRecording: (String) -> Unit,
    viewModel: TvheadendViewModel = hiltViewModel(),
    /** The live source can be TVHeadend, IPTV, Plex, Jellyfin or Emby. */
    onOpenSourceSettings: (String) -> Unit = { onOpenSettings() },
) {
    val state by viewModel.uiState.collectAsState()
    val titleArt by viewModel.titleArt.collectAsState()
    val selectTab = onSelectTab ?: viewModel::selectTab
    val swipeTabs = remember(state.capabilities) {
        buildList {
            add(TvheadendTab.LIVE)
            if (state.capabilities.guide) add(TvheadendTab.GUIDE)
            if (state.capabilities.recordings) add(TvheadendTab.RECORDINGS)
            if (state.capabilities.timers) add(TvheadendTab.TIMERS)
        }
    }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    val events = state.dashboard?.events.orEmpty()
    val channels = state.dashboard?.channels.orEmpty()
    val recordingArt: (TvhDvrEntry) -> LiveTvArt? = { liveTvRecordingArt(it, events, channels, titleArt) }
    val seriesArt: (TvhAutoRec) -> LiveTvArt? = { liveTvSeriesArt(it, events, channels, titleArt) }
    val canEditTimers = state.capabilities.editTimers && !state.offline
    val channelBrowser = state.tab == TvheadendTab.LIVE || state.tab == TvheadendTab.GUIDE
    val channelControls = rememberLiveTvBrowseControls(viewModel.browseState, "channels")
    val guideControls = rememberLiveTvBrowseControls(viewModel.browseState, "guide")
    val browseControls = if (state.tab == TvheadendTab.LIVE) channelControls else guideControls
    val channelListing = rememberLiveTvChannelListing(channels, events, state.guideOptions)
    val tags = remember(channels) { channels.flatMap { it.tags }.distinct().sorted() }
    val hasHbbtvCategory = remember(channels) { channels.any { !it.hbbtvUrl.isNullOrBlank() } }
    val filterModel = guideFilterModel(state.guideOptions, tags, hasHbbtvCategory, viewModel::setGuideOptions)
    // Crossfade keeps the previous tab mounted briefly. Each tab owns its requesters so
    // an outgoing channel row cannot register the incoming page's Up target or filter field.
    val pageFocusChains = remember(viewModel.browseState) {
        TvheadendTab.entries.associateWith { com.ambient.tv.ui.common.PageFocusChain() }
    }
    val pageFocusChain = pageFocusChains.getValue(state.tab)
    val showBrowseOptions = channelBrowser && state.configured && browseControls.showOptions
    LaunchedEffect(state.tab) {
        if (state.tab != TvheadendTab.LIVE) channelControls.leavePage()
        if (state.tab != TvheadendTab.GUIDE) guideControls.leavePage()
    }
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    com.ambient.tv.ui.common.RefreshOnEnterAndReconnect(setOf(state.source.id), viewModel::refreshAfterReconnect)
    ReportSidebarContentReady(!state.loading)
    if (onBack != null) {
        BackHandler(enabled = !showBrowseOptions && state.selectedEvent == null && state.selectedRecording == null && state.editing == null && pendingDelete == null) { onBack() }
    }
    val tokens = LocalUiTokens.current
    val tabFocusRequesters = remember {
        TvheadendTab.entries.associateWith { FocusRequester() }
    }
    val firstContentFocusRequester = remember { FocusRequester() }
    // Next/previous channel in the player follows the filtered, sorted list shown here.
    val watchChannel: (String) -> Unit = { channelUuid ->
        if (state.tab == TvheadendTab.LIVE || state.tab == TvheadendTab.GUIDE) {
            val dashboard = state.dashboard
            val visible = guideChannels(dashboard?.channels.orEmpty(), dashboard?.events.orEmpty().groupBy { it.channelUuid },
                System.currentTimeMillis() / 1000L, state.guideOptions).map { it.uuid }
            com.ambient.tv.livetv.LiveTvZapOrder.offer(state.source.id, visible)
        } else com.ambient.tv.livetv.LiveTvZapOrder.clear()
        onWatchChannel(channelUuid)
    }
    // Back from the player: the channel watched last (also after zapping) becomes the selection.
    val returnedChannel = remember { viewModel.consumeReturnFocus() }
    val contentFocusTarget = remember(state.tab, state.dashboard, state.guideOptions, returnedChannel) {
        liveTvFocusTarget(state.tab, state.dashboard, state.guideOptions,
            viewModel.focusedContentId(state.tab), viewModel.focusedRowId(state.tab), System.currentTimeMillis() / 1000L)
    }
    var initialContentFocusPending by remember(initialTab) { mutableStateOf(true) }
    com.ambient.tv.ui.common.BrowseFocusContentIdentity(listOf(state.source.id, state.tab, state.guideOptions))
    com.ambient.tv.ui.common.BrowseFocusInitialTarget(contentFocusTarget.contentId,
        contentFocusTarget.rowId.takeIf { state.tab == TvheadendTab.GUIDE })
    LaunchedEffect(initialTab) {
        viewModel.prepareTab(initialTab)
    }
    val sharedInitialFocus = com.ambient.tv.ui.common.LocalBrowseFocusReturnState.current != null
    LaunchedEffect(state.loading, state.tab, state.dashboard, initialContentFocusPending, contentFocusTarget) {
        if (sharedInitialFocus) return@LaunchedEffect
        val dashboard = state.dashboard ?: return@LaunchedEffect
        val hasContent = when (state.tab) {
            TvheadendTab.LIVE -> dashboard.channels.isNotEmpty()
            TvheadendTab.GUIDE -> dashboard.channels.isNotEmpty()
            TvheadendTab.RECORDINGS -> dashboard.recordings.isNotEmpty() || dashboard.failedRecordings.isNotEmpty()
            TvheadendTab.TIMERS -> dashboard.upcoming.isNotEmpty() || dashboard.autoRecs.isNotEmpty()
        }
        if (!initialContentFocusPending || state.loading || state.tab != initialTab || !hasContent) return@LaunchedEffect
        // Live TV and the Guide scroll to their target first and focus it themselves.
        if (state.tab == TvheadendTab.LIVE || state.tab == TvheadendTab.GUIDE) return@LaunchedEffect
        delay(140)
        repeat(4) {
            if (runCatching { firstContentFocusRequester.requestFocus() }.getOrDefault(false)) {
                initialContentFocusPending = false
                return@LaunchedEffect
            }
            delay(32)
        }
    }

    CompositionLocalProvider(
        LocalTvhPlaybackSource provides state.source.id,
        com.ambient.tv.ui.common.LocalFilterFocusState provides browseControls.filterFocusState,
    ) {
    Box(Modifier.fillMaxSize()) {
        PageBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .handheldSwipeTabs(
                    tabs = swipeTabs,
                    selected = state.tab,
                    onSelect = selectTab,
                    enabled = state.configured &&
                        !showBrowseOptions &&
                        state.selectedEvent == null &&
                        state.selectedRecording == null &&
                        state.editing == null &&
                        pendingDelete == null,
                )
                .focusProperties { onEnter = { if (showBrowseOptions) cancelFocusChange() } }
                .focusGroup()
                .padding(start = tokens.screenPadding, end = tokens.screenPadding, bottom = tokens.screenPadding, top = com.ambient.tv.ui.common.pageHeaderTop(tokens.screenPadding)),
            verticalArrangement = Arrangement.spacedBy(tokens.itemGap * 1.5f),
        ) {
        TvheadendHeader(
            state = state,
            tabFocusRequesters = tabFocusRequesters,
            onTab = selectTab,
            onRefresh = viewModel::refresh,
            focusChain = pageFocusChain,
            browseControls = browseControls.takeIf { channelBrowser && state.configured },
            activeFilterCount = filterModel.activeCount,
            visibleChannelCount = channelListing.visibleChannels.size.takeIf { channelBrowser },
        )

        OperationFeedbackScope(viewModel.feedback, "dashboard", state.selectedEvent == null && state.selectedRecording == null)
        OperationFeedbackScope(viewModel.feedback, "timers", state.tab == TvheadendTab.TIMERS && state.selectedEvent == null && state.selectedRecording == null)
        if (state.dashboard != null && state.error != null) {
            OperationFeedbackContent(state.error!!, Modifier.fillMaxWidth(),
                actionLabel = stringResource(R.string.home_retry), onAction = viewModel::refresh)
        }
        if (state.offline && state.dashboard != null) Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cached data stays browsable; play, record, timers and delete wait for the server.
            Text(stringResource(R.string.tvh_offline_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
            if (state.canWake) TvActionButton(text = stringResource(R.string.tvh_wake_server), onClick = viewModel::wakeServer,
                enabled = !state.waking, loading = state.waking)
        }
        if (state.tab == TvheadendTab.TIMERS) state.actionFeedback?.takeIf { it.scope == "timers" }?.let {
            OperationFeedbackContent(it, Modifier.fillMaxWidth())
        }
        when {
            !state.configured -> StatePanel(
                title = com.ambient.tv.ui.common.systemString(com.ambient.tv.ui.common.MediaSystem.entries.firstOrNull { it.name.equals(state.source.system, true) } ?: com.ambient.tv.ui.common.MediaSystem.TVHEADEND, R.string.tvh_not_configured),
                actionLabel = stringResource(R.string.tvh_open_settings),
                onAction = { onOpenSourceSettings(state.source.system) },
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 620.dp),
            )
            state.loading || (state.refreshing && when (state.tab) {
                TvheadendTab.LIVE -> state.dashboard?.channels.isNullOrEmpty()
                TvheadendTab.GUIDE -> state.dashboard?.channels.isNullOrEmpty()
                TvheadendTab.RECORDINGS -> state.dashboard?.let { it.recordings.isEmpty() && it.failedRecordings.isEmpty() } ?: true
                TvheadendTab.TIMERS -> state.dashboard?.let { it.upcoming.isEmpty() && it.autoRecs.isEmpty() } ?: true
            }) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            state.error != null && state.dashboard == null -> OperationFeedbackContent(
                feedback = state.error!!,
                layout = FeedbackLayout.PAGE,
                actionLabel = stringResource(R.string.tvh_refresh),
                onAction = viewModel::refresh,
                requestActionFocus = true,
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 680.dp),
            )
            else -> Crossfade(state.tab, animationSpec = tween(tokens.animationNormalMs), label = "tvhTab") { tab ->
                val dashboard = state.dashboard
                when (tab) {
                    TvheadendTab.LIVE -> LiveTvChannelBrowser(
                        browseState = viewModel.browseState,
                        options = state.guideOptions,
                        onOptions = viewModel::setGuideOptions,
                        controls = channelControls,
                        pageFocusChain = pageFocusChains.getValue(tab),
                        listing = channelListing,
                        channelList = true,
                        canWatch = !state.offline,
                        refreshing = state.refreshing,
                        channels = dashboard?.channels.orEmpty(),
                        events = dashboard?.events.orEmpty(),
                        upcoming = dashboard?.upcoming.orEmpty(),
                        imageHeaders = viewModel.imageHeaders,
                        onWatch = watchChannel,
                        onEvent = viewModel::selectEvent,
                        firstContentFocusRequester = firstContentFocusRequester,
                        focusTarget = contentFocusTarget,
                        onFocused = { id, rowId -> viewModel.onContentFocused(tab, id, rowId) },
                        restoreFocus = initialContentFocusPending && tab == initialTab && state.tab == tab,
                        onFocusRestored = { initialContentFocusPending = false },
                    )
                    TvheadendTab.GUIDE -> Guide(
                        options = state.guideOptions,
                        onOptions = viewModel::setGuideOptions,
                        controls = guideControls,
                        pageFocusChain = pageFocusChains.getValue(tab),
                        listing = channelListing,
                        onWatch = watchChannel,
                        canWatch = !state.offline,
                        refreshing = state.refreshing,
                        browseState = viewModel.browseState,
                        channels = dashboard?.channels.orEmpty(),
                        events = dashboard?.events.orEmpty(),
                        imageHeaders = viewModel.imageHeaders,
                        onEvent = viewModel::selectEvent,
                        firstContentFocusRequester = firstContentFocusRequester,
                        focusTarget = contentFocusTarget,
                        onFocused = { id, rowId -> viewModel.onContentFocused(tab, id, rowId) },
                        restoreFocus = initialContentFocusPending && tab == initialTab && state.tab == tab,
                        onFocusRestored = { initialContentFocusPending = false },
                    )
                    TvheadendTab.RECORDINGS -> RecordingList(
                        recordings = dashboard?.recordings.orEmpty(),
                        failedRecordings = dashboard?.failedRecordings.orEmpty(),
                        imageHeaders = viewModel.imageHeaders,
                        art = recordingArt,
                        canDelete = state.capabilities.deleteRecordings && !state.offline,
                        busy = state.actionInProgress,
                        onDelete = { pendingDelete = PendingDelete.Recording(it) },
                        onPlay = { if (state.offline) viewModel.selectRecording(it) else onPlayRecording(it.uuid) },
                        onSelect = viewModel::selectRecording,
                        firstContentFocusRequester = firstContentFocusRequester,
                        focusTarget = contentFocusTarget,
                        onFocused = { id -> viewModel.onContentFocused(tab, id) },
                    )
                    TvheadendTab.TIMERS -> TimerList(
                        timers = dashboard?.upcoming.orEmpty(),
                        autoRecs = dashboard?.autoRecs.orEmpty(),
                        canManage = state.capabilities.manageTimers && !state.offline,
                        canEdit = canEditTimers,
                        busy = state.actionInProgress,
                        imageHeaders = viewModel.imageHeaders,
                        art = recordingArt,
                        seriesArt = seriesArt,
                        onSelect = viewModel::selectRecording,
                        onEdit = { viewModel.edit(LiveTvEditTarget.Timer(it)) },
                        onEditSeries = { viewModel.edit(LiveTvEditTarget.Series(it)) },
                        onCancel = { pendingDelete = PendingDelete.Timer(it) },
                        onDeleteSeries = { pendingDelete = PendingDelete.Series(it) },
                        firstContentFocusRequester = firstContentFocusRequester,
                        focusTarget = contentFocusTarget,
                        onFocused = { id -> viewModel.onContentFocused(tab, id) },
                    )
                }
            }
        }
        }
        if (showBrowseOptions) LiveTvBrowseOptionsPanel(browseControls, filterModel)
    }

    val reminders by viewModel.reminders.collectAsState()
    state.selectedEvent?.let { event ->
        OperationFeedbackScope(viewModel.feedback, "event:${event.eventId}")
        val channel = state.dashboard?.channels?.firstOrNull { it.uuid == event.channelUuid }
        // A running programme recorded "from now" has its own timer without the EPG link.
        val timer = state.dashboard?.upcoming?.let { findProgrammeRecording(event, it) }
        val art = liveTvProgrammeArt(event, channels, titleArt)
        LaunchedEffect(event.eventId) { if (event.imageUrl == null) viewModel.requestTitleArt(listOf(event.title)) }
        EventDialog(
            event = event,
            timer = timer,
            art = art,
            imageHeaders = viewModel.imageHeaders,
            canRecord = state.capabilities.manageTimers && !state.offline,
            canRecordSeries = state.capabilities.seriesTimers && !state.offline,
            canWatch = !state.offline && channel != null && event.isLive(System.currentTimeMillis() / 1000L),
            busy = state.actionInProgress,
            message = state.actionFeedback?.takeIf { it.scope == "event:${event.eventId}" },
            onDismiss = { viewModel.selectEvent(null) },
            // Closed before the player opens, or it flashes up on return until selectTab clears it.
            onWatch = { channel?.let { viewModel.selectEvent(null); watchChannel(it.uuid) } },
            onRecord = { viewModel.schedule(event, false) },
            onRecordSeries = { viewModel.schedule(event, true) },
            onCancel = { timer?.let { pendingDelete = PendingDelete.Timer(it) } },
            reminded = reminders.any { it.sourceId == state.source.id && it.eventId == event.eventId },
            canRemind = event.startEpochSeconds > System.currentTimeMillis() / 1000L,
            onToggleReminder = { viewModel.toggleReminder(event) },
        )
    }

    state.selectedRecording?.let { entry ->
        OperationFeedbackScope(viewModel.feedback, "recording:${entry.uuid}")
        // A recording that is still running already has a URL but is not finished yet.
        val status = entry.scheduleStatus?.lowercase()
        // Failed entries are finished too: they are deleted, never cancelled like a timer.
        val failed = state.dashboard?.failedRecordings?.any { it.uuid == entry.uuid } == true
        val completed = !failed && (status == "completed" || (!entry.url.isNullOrBlank() && !entry.isActivelyRecording() && status != "scheduled"))
        val finished = completed || failed
        val upcoming = state.dashboard?.upcoming?.any { it.uuid == entry.uuid } == true
        RecordingDialog(
            entry = entry,
            completed = completed,
            finished = finished,
            art = recordingArt(entry),
            imageHeaders = viewModel.imageHeaders,
            canPlay = completed && !state.offline,
            canManage = !state.offline && entry.canDelete && if (finished) state.capabilities.deleteRecordings else state.capabilities.manageTimers,
            canEdit = canEditTimers && upcoming && !finished,
            onEdit = { viewModel.edit(LiveTvEditTarget.Timer(entry)) },
            busy = state.actionInProgress,
            message = state.actionFeedback?.takeIf { it.scope == "recording:${entry.uuid}" },
            onDismiss = { viewModel.selectRecording(null) },
            onPlay = { viewModel.selectRecording(null); onPlayRecording(entry.uuid) },
            onCancel = { pendingDelete = PendingDelete.Timer(entry) },
            onDelete = { pendingDelete = PendingDelete.Recording(entry) },
        )
    }

    OperationFeedbackScope(viewModel.feedback, "edit", state.editing != null)
    when (val editing = state.editing) {
        is LiveTvEditTarget.Timer -> LiveTvTimerEditDialog(
            title = editing.entry.title, series = false,
            paddingBefore = editing.entry.paddingBeforeMinutes, paddingAfter = editing.entry.paddingAfterMinutes,
            priority = editing.entry.priority, enabled = null,
            zeroIsServerDefault = state.source.system == "tvheadend",
            busy = state.actionInProgress, message = state.actionFeedback?.takeIf { it.scope == "edit" },
            onSave = { edit -> if (edit.isEmpty) viewModel.edit(null) else viewModel.updateTimer(editing.entry, edit) },
            onDismiss = { viewModel.edit(null) },
        )
        is LiveTvEditTarget.Series -> LiveTvTimerEditDialog(
            title = editing.entry.name, series = true,
            paddingBefore = editing.entry.paddingBeforeMinutes, paddingAfter = editing.entry.paddingAfterMinutes,
            priority = editing.entry.priority, enabled = editing.entry.enabled.takeIf { editing.entry.canDisable },
            zeroIsServerDefault = state.source.system == "tvheadend",
            busy = state.actionInProgress, message = state.actionFeedback?.takeIf { it.scope == "edit" },
            onSave = { edit -> if (edit.isEmpty) viewModel.edit(null) else viewModel.updateSeriesTimer(editing.entry, edit) },
            onDismiss = { viewModel.edit(null) },
        )
        null -> Unit
    }

    pendingDelete?.let { pending ->
        val (title, question, confirm) = when (pending) {
            is PendingDelete.Recording -> Triple(pending.entry.title, R.string.tvh_confirm_delete_recording, R.string.tvh_delete_recording)
            is PendingDelete.Timer -> Triple(pending.entry.title, R.string.tvh_confirm_cancel_timer,
                if (pending.entry.isActivelyRecording()) R.string.player_record_stop else R.string.tvh_cancel_recording)
            is PendingDelete.Series -> Triple(pending.entry.name, R.string.tvh_confirm_delete_series_timer, R.string.tvh_delete_series_timer)
        }
        LiveTvConfirmDialog(
            title = title, question = stringResource(question), confirmLabel = stringResource(confirm),
            busy = state.actionInProgress,
            onConfirm = {
                pendingDelete = null
                when (pending) {
                    is PendingDelete.Recording -> viewModel.delete(pending.entry)
                    is PendingDelete.Timer -> viewModel.cancel(pending.entry)
                    is PendingDelete.Series -> viewModel.deleteAutoRec(pending.entry)
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
    } // Playback source also covers the detail dialogs.
}

@Composable
internal fun TvheadendHeader(
    state: TvheadendUiState,
    tabFocusRequesters: Map<TvheadendTab, FocusRequester>,
    onTab: (TvheadendTab) -> Unit,
    onRefresh: () -> Unit,
    focusChain: com.ambient.tv.ui.common.PageFocusChain,
    browseControls: LiveTvBrowseControls? = null,
    activeFilterCount: Int = 0,
    visibleChannelCount: Int? = null,
) {
    @Composable fun Tab(tab: TvheadendTab, label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        val requester = tabFocusRequesters.getValue(tab)
        TvChip(stringResource(label), selected = state.tab == tab, modifier = Modifier.focusRequester(requester).then(if (state.tab == tab) Modifier.focusRequester(focusChain.selectedChip) else Modifier), icon = icon, onClick = { onTab(tab) })
    }
    // The tabs sit in a chip row under the title like every other provider page; Reload stays a header action.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    PageHeader(focusChain = focusChain, actions = {
        TvChip(stringResource(R.string.action_reload), icon = Icons.Filled.Refresh,
            loading = state.loading || state.refreshing, enabled = !state.actionInProgress, iconOnly = true,
            onClick = { browseControls?.filterFocusState?.keepControls(); onRefresh() })
        if (browseControls != null) com.ambient.tv.ui.common.ViewOptionsChip(
            activeCount = if (com.ambient.tv.ui.common.LocalFilterBarSetting.current.shown) 0 else activeFilterCount,
            onClick = browseControls::openOptions,
            modifier = Modifier.focusRequester(browseControls.optionsFocus),
        )
    }) {
        val tabTitle = stringResource(
            when (state.tab) {
                TvheadendTab.LIVE -> R.string.tvh_live
                TvheadendTab.GUIDE -> R.string.tvh_guide
                TvheadendTab.RECORDINGS -> R.string.tvh_recordings
                TvheadendTab.TIMERS -> R.string.tvh_timers
            },
        )
        // IPTV playlists have no provider mark: a plain title instead of borrowing TVHeadend's.
        if (state.source.system == "iptv") Text("${state.source.name} · $tabTitle", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        else com.ambient.tv.ui.common.SystemPageTitle(com.ambient.tv.ui.common.MediaSystem.entries.firstOrNull { it.name.equals(state.source.system, true) } ?: com.ambient.tv.ui.common.MediaSystem.TVHEADEND, title = tabTitle)
        state.dashboard?.let {
            Text(
                if (visibleChannelCount != null && visibleChannelCount != it.channels.size)
                    "${it.serverInfo.name} · ${stringResource(R.string.guide_count_filtered, visibleChannelCount, it.channels.size)}"
                else stringResource(R.string.tvh_server_status, it.serverInfo.name, visibleChannelCount ?: it.channels.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val visibleTabs = buildList {
        add(TvheadendTab.LIVE)
        if (state.capabilities.guide) add(TvheadendTab.GUIDE)
        if (state.capabilities.recordings) add(TvheadendTab.RECORDINGS)
        if (state.capabilities.timers) add(TvheadendTab.TIMERS)
    }
    com.ambient.tv.ui.common.PageChipRow(
        focusChain,
        selectedIndex = visibleTabs.indexOf(state.tab),
    ) {
        item { Tab(TvheadendTab.LIVE, R.string.tvh_live, Icons.Filled.Tv) }
        if (state.capabilities.guide) item { Tab(TvheadendTab.GUIDE, R.string.tvh_guide, Icons.Filled.Schedule) }
        if (state.capabilities.recordings) item { Tab(TvheadendTab.RECORDINGS, R.string.tvh_recordings, Icons.Filled.FiberManualRecord) }
        if (state.capabilities.timers) item { Tab(TvheadendTab.TIMERS, R.string.tvh_timers, Icons.Filled.Timer) }
    }
    }
}


@Composable
internal fun ChannelRow(
    channel: TvhChannel,
    current: TvhEpgEvent?,
    next: TvhEpgEvent?,
    recording: Boolean,
    imageHeaders: Map<String, String>,
    onWatch: (TvhChannel) -> Unit,
    onEvent: (TvhEpgEvent) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalHandheldUi.current) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.ambient.tv.companion.PlaybackBadge(livePlaybackIndicator("channel", channel.uuid))
                    current?.let { Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (recording) Text(stringResource(R.string.tvh_recording), color = MaterialTheme.colorScheme.primary)
                    next?.let { Text("${formatTime(it.startEpochSeconds)} · ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            leadingContent = { ArtworkImage(channel.iconUrl, channel.name, Modifier.size(44.dp), contentScale = ContentScale.Fit, headers = imageHeaders) },
            trailingContent = { if (current != null) androidx.compose.material3.IconButton(onClick = { onEvent(current) }) { androidx.compose.material3.Icon(Icons.Default.Info, stringResource(R.string.tvh_info)) } },
            modifier = modifier.clickable { onWatch(channel) },
        )
        return
    }

    var focused by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = { onWatch(channel) },
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = rowShape),
        modifier = modifier.fillMaxWidth().browseFocusTarget(channel.uuid).mediaFocusMotion(focused, focusedScale = 1.012f).activateOnTvKey { onWatch(channel) }.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), rowShape)
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), rowShape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(84.dp, 48.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.iconUrl != null) ArtworkImage(
                    model = channel.iconUrl,
                    title = channel.name,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit,
                    // Authentication is harmless for ticketed URLs and is required by
                    // servers that protect imagecache as well as the API.
                    headers = imageHeaders,
                    fallbackText = channel.numberLabel.orEmpty().ifBlank { channel.name.take(3) },
                )
                else Text(channel.numberLabel.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                com.ambient.tv.companion.PlaybackBadge(livePlaybackIndicator("channel", channel.uuid))
                MarqueeTitle(channel.name, focused = focused, style = MaterialTheme.typography.bodyMedium)
                channel.numberLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetaBadge(stringResource(R.string.tvh_now), emphasized = true)
                    if (recording) MetaBadge(stringResource(R.string.tvh_recording), emphasized = true)
                    MarqueeTitle(current?.title ?: "–", focused = focused, style = MaterialTheme.typography.titleSmall)
                }
                current?.let { event ->
                    // Advances while the list stays open, not only when the row recomposes.
                    val nowSeconds by androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000L, event.startEpochSeconds) {
                        while (true) { delay(30_000); value = System.currentTimeMillis() / 1000L }
                    }
                    val progress = ((nowSeconds - event.startEpochSeconds).toFloat() / event.durationSeconds.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator)
                }
                if (next != null) Text("${stringResource(R.string.tvh_next)} ${formatTime(next.startEpochSeconds)} · ${next.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current != null) {
                TvActionButton(text = stringResource(R.string.tvh_info), onClick = { onEvent(current) })
            }
        }
    }
}

@Composable
internal fun GuideEventCard(
    event: TvhEpgEvent,
    now: Long,
    onEvent: (TvhEpgEvent) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val width = (event.durationSeconds / 60f * 3.2f).roundToInt().coerceIn(150, 430).dp
    val live = event.isLive(now)
    val cardShape = RoundedCornerShape(10.dp)
    Surface(
        onClick = { onEvent(event) },
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = cardShape),
        modifier = modifier.width(width).browseFocusTarget("event:${event.eventId}", rowKey = event.channelUuid).mediaFocusMotion(focused).activateOnTvKey { onEvent(event) }.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .background(
                    when {
                        focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        live -> com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator.copy(alpha = 0.10f)
                        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                    }, cardShape,
                )
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), cardShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (live) MetaBadge(stringResource(R.string.tvh_live), emphasized = true)
                Text("${formatTime(event.startEpochSeconds)}–${formatTime(event.stopEpochSeconds)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RecordingList(
    recordings: List<TvhDvrEntry>,
    failedRecordings: List<TvhDvrEntry>,
    imageHeaders: Map<String, String>,
    art: (TvhDvrEntry) -> LiveTvArt?,
    canDelete: Boolean,
    busy: Boolean,
    onDelete: (TvhDvrEntry) -> Unit,
    onPlay: (TvhDvrEntry) -> Unit,
    onSelect: (TvhDvrEntry) -> Unit,
    firstContentFocusRequester: FocusRequester,
    focusTarget: TvhFocusTarget,
    onFocused: (String) -> Unit,
) {
    val rows = remember(recordings, failedRecordings) {
        (recordings.map { it to false } + failedRecordings.map { it to true }).sortedByDescending { it.first.startEpochSeconds }
    }
    if (rows.isEmpty()) {
        StatePanel(title = stringResource(R.string.tvh_no_recordings), modifier = Modifier.widthIn(max = 620.dp))
        return
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst { it.first.uuid == focusTarget.contentId }.coerceAtLeast(0),
    )
    com.ambient.tv.ui.common.RegisterBrowseFocusList(listState, rows.map { it.first.uuid })
    LazyColumn(Modifier.fillMaxSize().overlayLazyScrollbar(listState), allowUpExit = true, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 36.dp)) {
        items(rows, key = { it.first.uuid }) { (entry, failed) ->
            DvrRow(
                entry, completed = !failed, failed = failed, art = art(entry), imageHeaders = imageHeaders,
                onPrimary = { if (!failed) onPlay(entry) else onSelect(entry) },
                onInfo = { onSelect(entry) },
                onDelete = if (canDelete && entry.canDelete) ({ onDelete(entry) }) else null,
                busy = busy,
                onFocused = { onFocused(entry.uuid) },
                modifier = if (entry.uuid == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun TimerList(
    timers: List<TvhDvrEntry>,
    autoRecs: List<TvhAutoRec>,
    canManage: Boolean,
    canEdit: Boolean,
    busy: Boolean,
    imageHeaders: Map<String, String>,
    art: (TvhDvrEntry) -> LiveTvArt?,
    seriesArt: (TvhAutoRec) -> LiveTvArt?,
    onSelect: (TvhDvrEntry) -> Unit,
    onEdit: (TvhDvrEntry) -> Unit,
    onEditSeries: (TvhAutoRec) -> Unit,
    onCancel: (TvhDvrEntry) -> Unit,
    onDeleteSeries: (TvhAutoRec) -> Unit,
    firstContentFocusRequester: FocusRequester,
    focusTarget: TvhFocusTarget,
    onFocused: (String) -> Unit,
) {
    if (timers.isEmpty() && autoRecs.isEmpty()) {
        StatePanel(title = stringResource(R.string.tvh_no_timers), modifier = Modifier.widthIn(max = 620.dp))
        return
    }
    val targetIndex = timers.indexOfFirst { it.uuid == focusTarget.contentId }.takeIf { it >= 0 }
        ?: autoRecs.indexOfFirst { "autorec:${it.uuid}" == focusTarget.contentId }
            .takeIf { it >= 0 }?.let { timers.size + 1 + it }
        ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = targetIndex)
    com.ambient.tv.ui.common.RegisterBrowseFocusSlots(listState, buildList {
        timers.forEachIndexed { index, entry -> add(com.ambient.tv.ui.common.BrowseFocusSlot(
            com.ambient.tv.ui.common.BrowseFocusKey(null, entry.uuid, true), index)) }
        autoRecs.forEachIndexed { index, entry -> add(com.ambient.tv.ui.common.BrowseFocusSlot(
            com.ambient.tv.ui.common.BrowseFocusKey(null, "autorec:${entry.uuid}", true), timers.size + 1 + index)) }
    })
    LazyColumn(Modifier.fillMaxSize().overlayLazyScrollbar(listState), allowUpExit = true, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 36.dp)) {
        if (timers.isNotEmpty()) {
            items(timers, key = { it.uuid }) { entry ->
                DvrRow(
                    entry, completed = false, failed = false, art = art(entry), imageHeaders = imageHeaders,
                    onPrimary = { onSelect(entry) }, onInfo = { onSelect(entry) },
                    onEdit = if (canEdit && entry.canDelete) ({ onEdit(entry) }) else null,
                    onDelete = if (canManage && entry.canDelete) ({ onCancel(entry) }) else null,
                    busy = busy,
                    onFocused = { onFocused(entry.uuid) },
                    modifier = if (entry.uuid == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier,
                )
            }
        }
        if (autoRecs.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.tvh_series_timers),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                )
            }
            items(autoRecs, key = { "autorec:${it.uuid}" }) { entry ->
                val id = "autorec:${entry.uuid}"
                SeriesTimerRow(
                    entry, busy, canManage, onDeleteSeries,
                    art = seriesArt(entry), imageHeaders = imageHeaders,
                    onEdit = if (canEdit) ({ onEditSeries(entry) }) else null,
                    onFocused = { onFocused(id) },
                    initialFocusModifier = if (id == focusTarget.contentId) Modifier.focusRequester(firstContentFocusRequester) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun SeriesTimerRow(
    entry: TvhAutoRec,
    busy: Boolean,
    canManage: Boolean,
    onDelete: (TvhAutoRec) -> Unit,
    onFocused: () -> Unit,
    art: LiveTvArt?,
    imageHeaders: Map<String, String>,
    onEdit: (() -> Unit)?,
    initialFocusModifier: Modifier = Modifier,
) {
    // The row itself must not be a no-op focus target: keyboard/DPAD users should land
    // directly on its first action (Edit, else Delete) rather than on a dead Surface first.
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LiveTvArtBox(art, entry.name, imageHeaders, Modifier.size(96.dp, 54.dp))
        MetaBadge(stringResource(if (entry.enabled) R.string.tvh_series_timers else R.string.tvh_series_disabled), emphasized = entry.enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = listOfNotNull(entry.channelName?.takeIf { it.isNotBlank() }, entry.title?.takeIf { it.isNotBlank() && it != entry.name }).joinToString(" · ")
            if (secondary.isNotBlank()) Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onEdit != null) TvActionButton(
            text = stringResource(R.string.tvh_edit),
            onClick = onEdit,
            enabled = !busy,
            modifier = initialFocusModifier.browseFocusTarget("autorec:${entry.uuid}").onFocusChanged { if (it.isFocused) onFocused() },
        )
        if (canManage) TvActionButton(
            text = stringResource(R.string.tvh_delete),
            onClick = { onDelete(entry) },
            enabled = !busy,
            modifier = (if (onEdit == null) initialFocusModifier.browseFocusTarget("autorec:${entry.uuid}") else Modifier).onFocusChanged { if (it.isFocused) onFocused() },
        )
    }
}

@Composable
private fun DvrRow(
    entry: TvhDvrEntry,
    completed: Boolean,
    failed: Boolean,
    art: LiveTvArt?,
    imageHeaders: Map<String, String>,
    onPrimary: () -> Unit,
    onInfo: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var rowHasFocus by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(10.dp)
    // The actions are siblings of the clickable part, not inside it: D-pad Right searches beside
    // the focused surface, so buttons drawn inside its bounds could never be reached.
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .background(if (rowHasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), rowShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), rowShape)
            .padding(end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(onClick = onPrimary, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = rowShape),
            modifier = modifier.weight(1f).browseFocusTarget(entry.uuid).activateOnTvKey(onClick = onPrimary).onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LiveTvArtBox(art, entry.title, imageHeaders, Modifier.size(126.dp, 71.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MetaBadge(
                            when {
                                com.ambient.tv.tvheadend.data.recordingWasAborted(entry.status) -> stringResource(R.string.tvh_aborted)
                                failed -> stringResource(R.string.tvh_failed)
                                completed -> stringResource(R.string.tvh_completed)
                                entry.isActivelyRecording() -> stringResource(R.string.tvh_recording_active)
                                else -> stringResource(R.string.tvh_scheduled)
                            },
                            emphasized = !completed || failed,
                        )
                        MarqueeTitle(entry.title, focused = focused, style = MaterialTheme.typography.titleMedium)
                    }
                    if (completed) com.ambient.tv.companion.PlaybackBadge(livePlaybackIndicator("recording", entry.uuid))
                    Text("${formatDateTime(entry.startEpochSeconds)} · ${entry.channelName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    entry.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        TvActionButton(text = stringResource(R.string.tvh_info), onClick = onInfo)
        if (onEdit != null) TvActionButton(text = stringResource(R.string.tvh_edit), onClick = onEdit, enabled = !busy)
        if (onDelete != null) TvActionButton(text = stringResource(R.string.tvh_delete), onClick = onDelete, enabled = !busy)
    }
}

/** Row art: programme images cropped, channel logos fitted on the dark plate. */
@Composable
private fun LiveTvArtBox(art: LiveTvArt?, title: String, imageHeaders: Map<String, String>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.36f)), contentAlignment = Alignment.Center) {
        if (art != null) ArtworkImage(
            model = art.url, title = title,
            modifier = if (art.logo) Modifier.fillMaxSize().padding(10.dp) else Modifier.fillMaxSize(),
            contentScale = if (art.logo) ContentScale.Fit else ContentScale.Crop,
            headers = art.headers(imageHeaders),
        )
    }
}

@Composable
private fun EventDialog(
    event: TvhEpgEvent,
    timer: TvhDvrEntry?,
    art: LiveTvArt?,
    imageHeaders: Map<String, String>,
    canWatch: Boolean,
    canRecord: Boolean,
    canRecordSeries: Boolean,
    busy: Boolean,
    message: OperationFeedback?,
    onDismiss: () -> Unit,
    onWatch: () -> Unit,
    onRecord: () -> Unit,
    onRecordSeries: () -> Unit,
    onCancel: () -> Unit,
    reminded: Boolean = false,
    canRemind: Boolean = false,
    onToggleReminder: () -> Unit = {},
) {
    val playing = livePlaybackIndicator("channel", event.channelUuid)
    val openCurrent = com.ambient.tv.companion.LocalOpenActivePlayback.current
    val initialFocus = remember(event.eventId) { FocusRequester() }
    var pending by rememberPendingAction(busy)
    LaunchedEffect(event.eventId) { delay(60); runCatching { initialFocus.requestFocus() } }
    AppDialog(
        onDismiss = onDismiss,
        title = event.title,
        hint = "${formatDateTime(event.startEpochSeconds)} – ${formatTime(event.stopEpochSeconds)}",
        captureInitialFocus = false,
        widthFraction = if (LocalHandheldUi.current) null else 0.56f,
        minWidth = deviceDp(620.dp, 0.dp),
        maxWidth = 860.dp,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (event.isLive(System.currentTimeMillis() / 1000L)) MetaBadge(stringResource(R.string.tvh_live), emphasized = true)
                if (timer != null) MetaBadge(stringResource(R.string.tvh_recording), emphasized = true)
                Text(event.channelName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        footer = {
            if (canWatch) TvActionButton(text = stringResource(if (playing != null) R.string.playback_open else R.string.tvh_watch), onClick = { if (playing != null) openCurrent(playing) else onWatch() }, selected = true, modifier = Modifier.focusRequester(initialFocus))
            if (timer == null && canRecord) {
                TvActionButton(text = stringResource(R.string.tvh_record), onClick = { pending = R.string.tvh_record; onRecord() }, enabled = !busy, loading = busy && pending == R.string.tvh_record, modifier = if (!canWatch && !busy) Modifier.focusRequester(initialFocus) else Modifier)
                if (canRecordSeries && !event.seriesLink.isNullOrBlank()) TvActionButton(text = stringResource(R.string.tvh_record_series), onClick = { pending = R.string.tvh_record_series; onRecordSeries() }, enabled = !busy, loading = busy && pending == R.string.tvh_record_series)
            } else if (timer != null && canRecord && timer.canDelete) {
                TvActionButton(text = stringResource(if (timer.isActivelyRecording()) R.string.player_record_stop else R.string.tvh_cancel_recording), onClick = onCancel, loading = busy, modifier = if (!canWatch && !busy) Modifier.focusRequester(initialFocus) else Modifier)
            }
            if (canRemind) TvActionButton(text = stringResource(if (reminded) R.string.reminder_remove else R.string.reminder_add), onClick = onToggleReminder,
                selected = reminded)
            // Back takes the first focus whenever no record/cancel button above can (also for a timer that cannot be deleted).
            val recordActionFocusable = !busy && canRecord && (timer == null || timer.canDelete)
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss, modifier = if (!canWatch && !recordActionFocusable) Modifier.focusRequester(initialFocus) else Modifier)
        },
    ) {
        com.ambient.tv.companion.PlaybackStatusSection(playing)
        art?.let { LiveTvArtBox(it, event.title, imageHeaders, Modifier.fillMaxWidth().height(200.dp)) }
        event.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        (event.description ?: event.summary)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        message?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun RecordingDialog(
    entry: TvhDvrEntry,
    completed: Boolean,
    finished: Boolean,
    art: LiveTvArt?,
    imageHeaders: Map<String, String>,
    canPlay: Boolean,
    canManage: Boolean,
    canEdit: Boolean,
    onEdit: () -> Unit,
    busy: Boolean,
    message: OperationFeedback?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val playing = livePlaybackIndicator("recording", entry.uuid)
    val openCurrent = com.ambient.tv.companion.LocalOpenActivePlayback.current
    val initialFocus = remember(entry.uuid) { FocusRequester() }
    LaunchedEffect(entry.uuid) { delay(60); runCatching { initialFocus.requestFocus() } }
    AppDialog(
        onDismiss = onDismiss,
        title = entry.title,
        hint = formatDateTime(entry.startEpochSeconds),
        captureInitialFocus = false,
        widthFraction = if (LocalHandheldUi.current) null else 0.56f,
        minWidth = deviceDp(600.dp, 0.dp),
        maxWidth = 820.dp,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaBadge(stringResource(when {
                    com.ambient.tv.tvheadend.data.recordingWasAborted(entry.status) -> R.string.tvh_aborted
                    entry.scheduleStatus.equals("completedError", ignoreCase = true) ||
                        entry.scheduleStatus.equals("missed", ignoreCase = true) ||
                        entry.scheduleStatus.equals("invalid", ignoreCase = true) -> R.string.tvh_failed
                    completed -> R.string.tvh_completed
                    entry.isActivelyRecording() -> R.string.tvh_recording_active
                    else -> R.string.tvh_scheduled
                }), emphasized = !completed)
                Text(entry.channelName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        footer = {
            if (finished) {
                if (canPlay) TvActionButton(text = stringResource(if (playing != null) R.string.playback_open else R.string.tvh_watch), onClick = { if (playing != null) openCurrent(playing) else onPlay() }, selected = true, modifier = Modifier.focusRequester(initialFocus))
                if (canManage) TvActionButton(text = stringResource(R.string.tvh_delete_recording), onClick = onDelete, loading = busy, modifier = if (!canPlay && !busy) Modifier.focusRequester(initialFocus) else Modifier)
            } else {
                if (canEdit) TvActionButton(text = stringResource(R.string.tvh_edit), onClick = onEdit, enabled = !busy, modifier = if (!busy) Modifier.focusRequester(initialFocus) else Modifier)
                if (canManage) TvActionButton(text = stringResource(if (entry.isActivelyRecording()) R.string.player_record_stop else R.string.tvh_cancel_recording), onClick = onCancel, loading = busy, modifier = if (!canEdit && !busy) Modifier.focusRequester(initialFocus) else Modifier)
            }
            val actionTakesFocus = (finished && canPlay) || (!busy && (canManage || (canEdit && !finished)))
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss, modifier = if (!actionTakesFocus) Modifier.focusRequester(initialFocus) else Modifier)
        },
    ) {
        com.ambient.tv.companion.PlaybackStatusSection(playing)
        art?.let { LiveTvArtBox(it, entry.title, imageHeaders, Modifier.fillMaxWidth().height(200.dp)) }
        entry.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        message?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
    }
}

private fun formatTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

private fun formatDateTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
