package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.guideChannels
import com.ambient.tv.livetv.guideEvents

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
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val LocalTvhPlaybackSource = compositionLocalOf { "" }

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
) {
    val state by viewModel.uiState.collectAsState()
    com.ambient.tv.ui.common.RetryOfflineBrowse(viewModel::retryOffline)
    ReportSidebarContentReady(!state.loading)
    if (onBack != null) {
        BackHandler(enabled = state.selectedEvent == null && state.selectedRecording == null) { onBack() }
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
    val contentFocusTarget = remember(state.tab, state.dashboard, state.guideOptions) {
        val dashboard = state.dashboard
        val savedId = viewModel.focusedContentId(state.tab)
        val savedRowId = viewModel.focusedRowId(state.tab)
        when (state.tab) {
            TvheadendTab.LIVE -> {
                val ids = guideChannels(dashboard?.channels.orEmpty(),
                    dashboard?.events.orEmpty().groupBy { it.channelUuid },
                    System.currentTimeMillis() / 1000L, state.guideOptions).map { it.uuid }
                TvhFocusTarget(savedId?.takeIf { it in ids } ?: ids.firstOrNull())
            }
            TvheadendTab.GUIDE -> {
                val now = System.currentTimeMillis() / 1000L
                val eventsByChannel = dashboard?.events.orEmpty().groupBy { it.channelUuid }
                val rows = guideChannels(dashboard?.channels.orEmpty(), eventsByChannel, now, state.guideOptions).map { channel ->
                    channel.uuid to guideEvents(eventsByChannel[channel.uuid].orEmpty(), now)
                }
                val savedRow = rows.firstOrNull { (rowId, events) ->
                    rowId == savedRowId && (savedId == "channel:$rowId" || events.any { "event:${it.eventId}" == savedId })
                }
                val row = savedRow ?: rows.firstOrNull { (rowId, events) -> savedId == "channel:$rowId" || events.any { "event:${it.eventId}" == savedId } }
                    ?: rows.firstOrNull()
                val event = if (savedId == "channel:${row?.first}") null else savedId?.let { id -> row?.second?.firstOrNull { "event:${it.eventId}" == id } }
                    ?: row?.second?.firstOrNull()
                TvhFocusTarget(event?.let { "event:${it.eventId}" } ?: row?.first?.let { "channel:$it" }, row?.first)
            }
            TvheadendTab.RECORDINGS -> {
                val rows = (dashboard?.recordings.orEmpty() + dashboard?.failedRecordings.orEmpty())
                    .sortedByDescending { it.startEpochSeconds }
                val id = savedId?.takeIf { value -> rows.any { it.uuid == value } } ?: rows.firstOrNull()?.uuid
                TvhFocusTarget(id)
            }
            TvheadendTab.TIMERS -> {
                val ids = dashboard?.upcoming.orEmpty().map { it.uuid } +
                    dashboard?.autoRecs.orEmpty().map { "autorec:${it.uuid}" }
                TvhFocusTarget(savedId?.takeIf { it in ids } ?: ids.firstOrNull())
            }
        }
    }
    var initialContentFocusPending by remember(initialTab) { mutableStateOf(true) }
    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(initialTab) {
        viewModel.selectTab(initialTab)
    }
    LaunchedEffect(state.loading, state.tab, state.dashboard, initialContentFocusPending, contentFocusTarget) {
        val dashboard = state.dashboard ?: return@LaunchedEffect
        val hasContent = when (state.tab) {
            TvheadendTab.LIVE -> dashboard.channels.isNotEmpty()
            TvheadendTab.GUIDE -> dashboard.channels.isNotEmpty()
            TvheadendTab.RECORDINGS -> dashboard.recordings.isNotEmpty() || dashboard.failedRecordings.isNotEmpty()
            TvheadendTab.TIMERS -> dashboard.upcoming.isNotEmpty() || dashboard.autoRecs.isNotEmpty()
        }
        if (!initialContentFocusPending || state.loading || state.tab != initialTab || !hasContent) return@LaunchedEffect
        delay(140)
        repeat(4) {
            if (runCatching { firstContentFocusRequester.requestFocus() }.getOrDefault(false)) {
                initialContentFocusPending = false
                return@LaunchedEffect
            }
            delay(32)
        }
    }

    CompositionLocalProvider(LocalTvhPlaybackSource provides state.source.id) {
    Box(Modifier.fillMaxSize()) {
        PageBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier.fillMaxSize().padding(tokens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(tokens.itemGap * 1.5f),
        ) {
        TvheadendHeader(
            state = state,
            tabFocusRequesters = tabFocusRequesters,
            onTab = onSelectTab ?: viewModel::selectTab,
            onRefresh = viewModel::refresh,
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
                onAction = onOpenSettings,
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
                    )
                    TvheadendTab.GUIDE -> Guide(
                        options = state.guideOptions,
                        onOptions = viewModel::setGuideOptions,
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
                    )
                    TvheadendTab.RECORDINGS -> RecordingList(
                        recordings = dashboard?.recordings.orEmpty(),
                        failedRecordings = dashboard?.failedRecordings.orEmpty(),
                        imageHeaders = viewModel.imageHeaders,
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
                        busy = state.actionInProgress,
                        imageHeaders = viewModel.imageHeaders,
                        onSelect = viewModel::selectRecording,
                        onDeleteSeries = viewModel::deleteAutoRec,
                        firstContentFocusRequester = firstContentFocusRequester,
                        focusTarget = contentFocusTarget,
                        onFocused = { id -> viewModel.onContentFocused(tab, id) },
                    )
                }
            }
        }
        }
    }

    val reminders by viewModel.reminders.collectAsState()
    state.selectedEvent?.let { event ->
        OperationFeedbackScope(viewModel.feedback, "event:${event.eventId}")
        val channel = state.dashboard?.channels?.firstOrNull { it.uuid == event.channelUuid }
        // A running programme recorded "from now" has its own timer without the EPG link.
        val timer = state.dashboard?.upcoming?.let { findProgrammeRecording(event, it) }
        EventDialog(
            event = event,
            timer = timer,
            canRecord = state.capabilities.manageTimers && !state.offline,
            canRecordSeries = state.capabilities.seriesTimers && !state.offline,
            canWatch = !state.offline && channel != null && event.isLive(System.currentTimeMillis() / 1000L),
            busy = state.actionInProgress,
            message = state.actionFeedback?.takeIf { it.scope == "event:${event.eventId}" },
            onDismiss = { viewModel.selectEvent(null) },
            onWatch = { channel?.let { watchChannel(it.uuid) } },
            onRecord = { viewModel.schedule(event, false) },
            onRecordSeries = { viewModel.schedule(event, true) },
            onCancel = { timer?.let(viewModel::cancel) },
            reminded = reminders.any { it.sourceId == state.source.id && it.eventId == event.eventId },
            canRemind = event.startEpochSeconds > System.currentTimeMillis() / 1000L,
            onToggleReminder = { viewModel.toggleReminder(event) },
        )
    }

    state.selectedRecording?.let { entry ->
        OperationFeedbackScope(viewModel.feedback, "recording:${entry.uuid}")
        // A recording that is still running already has a URL but is not finished yet.
        val status = entry.scheduleStatus?.lowercase()
        val completed = status == "completed" || (!entry.url.isNullOrBlank() && !entry.isActivelyRecording() && status != "scheduled")
        RecordingDialog(
            entry = entry,
            completed = completed,
            canPlay = completed && !state.offline,
            canManage = !state.offline && entry.canDelete && if (completed) state.capabilities.deleteRecordings else state.capabilities.manageTimers,
            busy = state.actionInProgress,
            message = state.actionFeedback?.takeIf { it.scope == "recording:${entry.uuid}" },
            onDismiss = { viewModel.selectRecording(null) },
            onPlay = { onPlayRecording(entry.uuid) },
            onCancel = { viewModel.cancel(entry) },
            onDelete = { viewModel.delete(entry) },
        )
    }
    } // Playback source also covers the detail dialogs.
}

@Composable
private fun TvheadendHeader(
    state: TvheadendUiState,
    tabFocusRequesters: Map<TvheadendTab, FocusRequester>,
    onTab: (TvheadendTab) -> Unit,
    onRefresh: () -> Unit,
) {
    PageHeader(actions = {
        TvChip(stringResource(R.string.tvh_live), selected = state.tab == TvheadendTab.LIVE, modifier = Modifier.focusRequester(tabFocusRequesters.getValue(TvheadendTab.LIVE)), icon = Icons.Filled.Tv, iconOnly = true, onClick = { onTab(TvheadendTab.LIVE) })
        if (state.capabilities.guide) TvChip(stringResource(R.string.tvh_guide), selected = state.tab == TvheadendTab.GUIDE, modifier = Modifier.focusRequester(tabFocusRequesters.getValue(TvheadendTab.GUIDE)), icon = Icons.Filled.Schedule, iconOnly = true, onClick = { onTab(TvheadendTab.GUIDE) })
        if (state.capabilities.recordings) TvChip(stringResource(R.string.tvh_recordings), selected = state.tab == TvheadendTab.RECORDINGS, modifier = Modifier.focusRequester(tabFocusRequesters.getValue(TvheadendTab.RECORDINGS)), icon = Icons.Filled.FiberManualRecord, iconOnly = true, onClick = { onTab(TvheadendTab.RECORDINGS) })
        if (state.capabilities.timers) TvChip(stringResource(R.string.tvh_timers), selected = state.tab == TvheadendTab.TIMERS, modifier = Modifier.focusRequester(tabFocusRequesters.getValue(TvheadendTab.TIMERS)), icon = Icons.Filled.Timer, iconOnly = true, onClick = { onTab(TvheadendTab.TIMERS) })
        TvChip(stringResource(R.string.action_reload), icon = Icons.Filled.Refresh, loading = state.loading || state.refreshing, iconOnly = true, onClick = onRefresh)
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
                stringResource(R.string.tvh_server_status, it.serverInfo.name, it.channels.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        modifier = modifier.fillMaxWidth().mediaFocusMotion(focused, focusedScale = 1.012f).activateOnTvKey { onWatch(channel) }.onFocusChanged {
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
        modifier = modifier.width(width).mediaFocusMotion(focused).activateOnTvKey { onEvent(event) }.onFocusChanged {
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
    LazyColumn(Modifier.fillMaxSize().overlayLazyScrollbar(listState), allowUpExit = true, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 36.dp)) {
        items(rows, key = { it.first.uuid }) { (entry, failed) ->
            DvrRow(
                entry, completed = !failed, failed = failed, imageHeaders = imageHeaders,
                onPrimary = { if (!failed) onPlay(entry) else onSelect(entry) },
                onInfo = { onSelect(entry) },
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
    busy: Boolean,
    imageHeaders: Map<String, String>,
    onSelect: (TvhDvrEntry) -> Unit,
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
    LazyColumn(Modifier.fillMaxSize().overlayLazyScrollbar(listState), allowUpExit = true, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 36.dp)) {
        if (timers.isNotEmpty()) {
            items(timers, key = { it.uuid }) { entry ->
                DvrRow(
                    entry, completed = false, failed = false, imageHeaders = imageHeaders,
                    onPrimary = { onSelect(entry) }, onInfo = { onSelect(entry) },
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
    initialFocusModifier: Modifier = Modifier,
) {
    // The row itself must not be a no-op focus target: keyboard/DPAD users should land
    // directly on the actionable Delete button rather than on a dead Surface first.
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MetaBadge(stringResource(R.string.tvh_series_timers), emphasized = entry.enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val secondary = listOfNotNull(entry.channelName?.takeIf { it.isNotBlank() }, entry.title?.takeIf { it.isNotBlank() && it != entry.name }).joinToString(" · ")
            if (secondary.isNotBlank()) Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        var pending by rememberPendingAction(busy)
        if (canManage) TvActionButton(
            text = stringResource(R.string.tvh_delete_series_timer),
            onClick = { pending = entry.uuid; onDelete(entry) },
            enabled = !busy,
            loading = busy && pending == entry.uuid,
            modifier = initialFocusModifier.onFocusChanged { if (it.isFocused) onFocused() },
        )
    }
}

@Composable
private fun DvrRow(
    entry: TvhDvrEntry,
    completed: Boolean,
    failed: Boolean,
    imageHeaders: Map<String, String>,
    onPrimary: () -> Unit,
    onInfo: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(10.dp)
    Surface(onClick = onPrimary, colors = transparentSurfaceColors(), scale = noSurfaceScale(), shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = rowShape), modifier = modifier.fillMaxWidth().mediaFocusMotion(focused, focusedScale = 1.012f).activateOnTvKey(onClick = onPrimary).onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) onFocused()
    }) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), rowShape)
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f), rowShape)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(126.dp, 71.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.36f))) {
                val art = entry.imageUrl ?: entry.fanartUrl
                if (art != null) ArtworkImage(model = art, title = entry.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, headers = imageHeaders)
            }
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
            TvActionButton(text = stringResource(R.string.tvh_info), onClick = onInfo)
        }
    }
}

@Composable
private fun EventDialog(
    event: TvhEpgEvent,
    timer: TvhDvrEntry?,
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
        event.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        (event.description ?: event.summary)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        message?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun RecordingDialog(
    entry: TvhDvrEntry,
    completed: Boolean,
    canPlay: Boolean,
    canManage: Boolean,
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
            if (completed) {
                if (canPlay) TvActionButton(text = stringResource(if (playing != null) R.string.playback_open else R.string.tvh_watch), onClick = { if (playing != null) openCurrent(playing) else onPlay() }, selected = true, modifier = Modifier.focusRequester(initialFocus))
                if (canManage) TvActionButton(text = stringResource(R.string.tvh_delete_recording), onClick = onDelete, loading = busy)
            } else if (canManage) {
                TvActionButton(text = stringResource(if (entry.isActivelyRecording()) R.string.player_record_stop else R.string.tvh_cancel_recording), onClick = onCancel, loading = busy, modifier = if (!busy) Modifier.focusRequester(initialFocus) else Modifier)
            }
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss, modifier = if ((!completed || !canPlay) && (busy || !canManage)) Modifier.focusRequester(initialFocus) else Modifier)
        },
    ) {
        com.ambient.tv.companion.PlaybackStatusSection(playing)
        entry.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        message?.let { OperationFeedbackContent(it, Modifier.fillMaxWidth()) }
    }
}

private fun formatTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

private fun formatDateTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("EEE dd.MM. HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))
