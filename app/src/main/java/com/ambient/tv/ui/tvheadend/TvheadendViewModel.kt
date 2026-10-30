package com.ambient.tv.ui.tvheadend

import com.ambient.tv.notifications.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.ambient.tv.notifications.NoticeKind

import com.ambient.tv.util.suspendResult
import kotlinx.coroutines.Job
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.ambient.tv.livetv.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.tvheadend.domain.TvhAutoRec
import com.ambient.tv.tvheadend.domain.TvhChannel
import com.ambient.tv.tvheadend.domain.TvhDashboard
import com.ambient.tv.tvheadend.domain.TvhDvrEntry
import com.ambient.tv.tvheadend.domain.TvhEpgEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TvheadendTab { LIVE, GUIDE, RECORDINGS, TIMERS, HBBTV }

sealed interface LiveTvEditTarget {
    data class Timer(val entry: TvhDvrEntry) : LiveTvEditTarget
    data class Series(val entry: TvhAutoRec) : LiveTvEditTarget
}

data class TvheadendUiState(
    val configured: Boolean = false,
    val offline: Boolean = false,
    /** Offline TVHeadend with a MAC address: "wake server" is offered. */
    val canWake: Boolean = false,
    val waking: Boolean = false,
    val guideOptions: GuideOptions = GuideOptions(),
    val source: LiveTvSource = LiveTvSource("tvheadend", "tvheadend", "TVHeadend"),
    val capabilities: LiveTvCapabilities = LiveTvCapabilities(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    /** A cached or channel-only dashboard does not prove optional tabs have finished loading. */
    val initialDashboardSettled: Boolean = false,
    val tab: TvheadendTab = TvheadendTab.LIVE,
    val dashboard: TvhDashboard? = null,
    val selectedEvent: TvhEpgEvent? = null,
    val selectedRecording: TvhDvrEntry? = null,
    /** Timer or series timer whose settings dialog is open. */
    val editing: LiveTvEditTarget? = null,
    val actionInProgress: Boolean = false,
    val actionFeedback: OperationFeedback? = null,
    val error: OperationFeedback? = null,
)

@HiltViewModel
class TvheadendViewModel @Inject constructor(
    private val preferences: AppPreferences,
    registry: LiveTvRegistry,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val reminderStore: com.ambient.tv.livetv.LiveTvReminderStore,
    private val tvheadendConnection: com.ambient.tv.server.TvheadendConnectionManager,
    private val artwork: LiveTvArtworkRepository,
) : ViewModel() {
    val reminders = reminderStore.reminders.also { reminderStore.refresh() }

    /** Remind before the programme starts (any live source; works without recording rights). */
    fun toggleReminder(event: com.ambient.tv.tvheadend.domain.TvhEpgEvent) {
        val added = reminderStore.toggle(com.ambient.tv.livetv.LiveTvReminder(
            sourceId = repository.source.id, channelUuid = event.channelUuid, channelName = event.channelName,
            eventId = event.eventId, title = event.title, startEpochSeconds = event.startEpochSeconds,
        ))
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            "reminder:${event.eventId}", if (added) com.ambient.tv.R.string.reminder_set else com.ambient.tv.R.string.reminder_removed,
            subject = event.title, kind = com.ambient.tv.notifications.NoticeKind.LIBRARY, interruptPlayback = false,
        ))
    }
    val feedback = OperationFeedbackReporter()
    private val repository = registry.repository(savedStateHandle.get<String>("sourceId"))
    private val hostSource = liveTvHostSource(repository.source.id, repository.source.system)
    private fun offlineNow() = liveTvSourceOffline(availability.hasNetwork(), hosts.health(hostSource))
    val browseState = registry.browseState(repository)
    private val _uiState = MutableStateFlow(TvheadendUiState(
        configured = true, source = repository.source,
        offline = offlineNow(), canWake = offlineNow() && canWake(),
        guideOptions = preferences.guideOptions(repository.source.id),
        dashboard = browseState.dashboard, capabilities = browseState.capabilities,
        loading = browseState.dashboard == null,
        tab = TvheadendTab.entries.firstOrNull {
            it.name.equals(savedStateHandle.get<String>("tab"), ignoreCase = true)
        } ?: TvheadendTab.LIVE,
    ))
    val uiState: StateFlow<TvheadendUiState> = kotlinx.coroutines.flow.combine(
        _uiState, hosts.states.map { hosts.health(hostSource) }.distinctUntilChanged(), availability.changes,
    ) { state, health, _ ->
        val offline = liveTvSourceOffline(availability.hasNetwork(), health)
        state.copy(offline = offline, canWake = offline && canWake())
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _uiState.value)

    private val isTvheadend get() = repository.source.system == "tvheadend"
    private fun canWake() = isTvheadend && !preferences.tvheadendMacAddress.isNullOrBlank()

    /** "Wake server": the shared reconnect signal refreshes the visible screen afterwards. */
    fun wakeServer() {
        if (!canWake() || _uiState.value.waking) return
        _uiState.update { it.copy(waking = true) }
        viewModelScope.launch {
            // Same path as Plex's reconnect: wake with its packets and notices, then connect.
            suspendResult { tvheadendConnection.reconnect() }
            _uiState.update { it.copy(waking = false) }
        }
    }

    /** Channel/EPG icon URLs point at the TVHeadend server and need the same Basic Auth
     * credentials as the API calls - Coil doesn't share those with [repository]'s own client. */
    // Composition reads this; a source removed in settings must not crash the still-open screen.
    val imageHeaders: Map<String, String> get() = runCatching { repository.authHeaders() }.getOrDefault(emptyMap())

    private fun focusStateKey(tab: TvheadendTab) = "${repository.source.id}:${tab.name.lowercase()}"
    fun focusedContentId(tab: TvheadendTab): String? = preferences.sectionFocus(focusStateKey(tab))
    fun focusedRowId(tab: TvheadendTab): String? = preferences.sectionFocusRow(focusStateKey(tab))
    fun onContentFocused(tab: TvheadendTab, contentId: String, rowId: String? = null) {
        preferences.setSectionFocus(focusStateKey(tab), contentId)
        preferences.setSectionFocusRow(focusStateKey(tab), rowId)
    }

    /**
     * The channel watched last (start or zap) becomes the Live TV and Guide selection once,
     * when this screen is entered again. Returns it, or null if none or hidden by the filters.
     */
    fun consumeReturnFocus(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): String? {
        val channelUuid = LiveTvReturnFocus.take(repository.source.id) ?: return null
        val state = _uiState.value
        val selections = returnFocusSelections(channelUuid, state.dashboard, state.guideOptions, nowEpochSeconds) ?: return null
        selections.forEach { (tab, target) -> target.contentId?.let { onContentFocused(tab, it, target.rowId) } }
        return channelUuid
    }

    private var preparedTab: TvheadendTab? = null

    /** Apply a destination's tab once; returning must keep the current tab and open selection. */
    fun prepareTab(tab: TvheadendTab) {
        if (preparedTab == tab) return
        preparedTab = tab
        if (_uiState.value.tab != tab) selectTab(tab)
    }

    fun selectTab(tab: TvheadendTab) = _uiState.update { it.copy(tab = tab, selectedEvent = null, selectedRecording = null, editing = null, actionFeedback = null) }
    fun setGuideOptions(options: GuideOptions) {
        preferences.setGuideOptions(repository.source.id, options)
        _uiState.update { it.copy(guideOptions = options) }
    }

    fun selectEvent(event: TvhEpgEvent?) = _uiState.update { it.copy(selectedEvent = event, selectedRecording = null, actionFeedback = null) }
    fun selectRecording(entry: TvhDvrEntry?) = _uiState.update { it.copy(selectedRecording = entry, selectedEvent = null, actionFeedback = null) }
    fun edit(target: LiveTvEditTarget?) {
        if (target != null && (!_uiState.value.capabilities.editTimers || uiState.value.offline)) return
        _uiState.update { it.copy(editing = target, actionFeedback = null) }
    }

    /** Shared with the player; only composed cards and opened details request their own art. */
    val titleArt = artwork.titleArt
    fun requestTitleArt(titles: Collection<String>, scope: kotlinx.coroutines.CoroutineScope = viewModelScope) =
        artwork.requestTitleArt(titles, scope)

    private var refreshJob: Job? = null
    private val refreshPolicy = LiveTvRefreshPolicy()
    private var refreshNeedsRetry = false

    fun retryOffline() {
        if ((uiState.value.offline || refreshNeedsRetry) && availability.hasNetwork() && refreshJob?.isActive != true)
            load(force = true, announceSuccess = false)
    }

    fun refresh() { if (!_uiState.value.actionInProgress) load(force = true) }

    /** Each entry and recovery fetches fresh data, retaining the shown dashboard and selection. */
    fun refreshAfterReconnect() {
        refreshPolicy.reconnected()
        load(force = false)
    }

    private fun load(force: Boolean, announceSuccess: Boolean = force) {
        val request = refreshPolicy.request(force, announceSuccess, _uiState.value.actionInProgress) ?: return
        refreshJob?.cancel()
        val ticket = feedback.begin("dashboard")
        _uiState.update { it.copy(loading = it.dashboard == null, refreshing = it.dashboard != null, error = null) }
        refreshJob = viewModelScope.launch {
            val owner = "livetv:${repository.source.id}"
            val namespace = snapshots.namespace(owner, repository.authHeaders().toSortedMap().toString() +
                "|${preferences.tvheadendHost}:${preferences.tvheadendPort}:${preferences.tvheadendUsername}")
            snapshots.read<LiveTvDashboard>(namespace, "dashboard")?.let { cached ->
                if (_uiState.value.dashboard == null) _uiState.update { it.copy(dashboard = cached, offline = offlineNow(), canWake = offlineNow() && canWake(),
                    capabilities = it.capabilities.copy(available = true, guide = cached.events.isNotEmpty(), recordings = cached.recordings.isNotEmpty()), loading = false, refreshing = true) }
            }
            _uiState.update { it.copy(loading = it.dashboard == null, refreshing = it.dashboard != null, error = null) }
            suspendResult {
                val prefetch = repository.prefetchDashboard(request.force)
                val caps = prefetch.capabilities
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(capabilities = caps) }
                if (caps.available && _uiState.value.dashboard == null) {
                    val channels = prefetch.channels ?: repository.channels(request.force)
                    currentCoroutineContext().ensureActive()
                    _uiState.update { it.copy(loading = false, refreshing = true, dashboard = LiveTvDashboard(
                        LiveTvServerInfo(repository.source.name, null, null), channels, emptyList(), emptyList(), emptyList())) }
                }
                repository.dashboard(force = request.force, prefetch = prefetch)
            }.also { currentCoroutineContext().ensureActive() }.fold(
                onSuccess = { fresh ->
                    val old = _uiState.value.dashboard
                    val merge = LiveTvDashboardMergePolicy(fresh.warnings)
                    val dashboard = if (fresh.warnings.isEmpty() || old == null) fresh else fresh.copy(
                        events = merge.merge(LiveTvDashboardPart.EPG, fresh.events, old.events),
                        recordings = merge.merge(LiveTvDashboardPart.RECORDINGS, fresh.recordings, old.recordings),
                        upcoming = merge.merge(LiveTvDashboardPart.TIMERS, fresh.upcoming, old.upcoming),
                        failedRecordings = merge.merge(LiveTvDashboardPart.FAILED_RECORDINGS, fresh.failedRecordings, old.failedRecordings),
                        autoRecs = merge.merge(LiveTvDashboardPart.SERIES_TIMERS, fresh.autoRecs, old.autoRecs),
                    )
                    if (fresh.warnings.isEmpty()) snapshots.write(owner, namespace, "dashboard", dashboard.copy(
                        recordings = dashboard.recordings.map { it.copy(url = null) },
                        upcoming = dashboard.upcoming.map { it.copy(url = null) },
                        failedRecordings = dashboard.failedRecordings.map { it.copy(url = null) },
                    ))
                    browseState.update(dashboard, _uiState.value.capabilities)
                    val result = feedback.complete(ticket, OperationFeedback(
                        "refresh:${repository.source.id}", "dashboard",
                        if (dashboard.warnings.isEmpty()) R.string.notice_refresh_done else R.string.feedback_live_partial,
                        if (dashboard.warnings.isEmpty()) FeedbackPhase.SUCCESS else FeedbackPhase.WARNING,
                        detail = if (dashboard.warnings.isEmpty()) null else R.string.feedback_live_partial_hint,
                        system = repository.source.system, subject = repository.source.name, kind = NoticeKind.LIBRARY,
                        delivery = if (request.announceSuccess && dashboard.warnings.isEmpty()) FeedbackDelivery.ACTION else FeedbackDelivery.INLINE,
                    )) ?: return@launch
                    refreshPolicy.completed(request)
                    // Stored data may need another read, but says nothing about reachability.
                    refreshNeedsRetry = repository.servingCachedData
                    val offline = offlineNow()
                    _uiState.update { it.copy(configured = true, offline = offline, canWake = offline && canWake(),
                        loading = false, refreshing = false, initialDashboardSettled = true,
                        dashboard = dashboard,
                        selectedEvent = it.selectedEvent?.let { selected ->
                            dashboard.events.firstOrNull { event -> event.eventId == selected.eventId && event.channelUuid == selected.channelUuid }
                        },
                        selectedRecording = it.selectedRecording?.let { selected ->
                            // A timer may have moved to completed recordings while its old area failed.
                            (fresh.upcoming + fresh.recordings + fresh.failedRecordings +
                                dashboard.upcoming + dashboard.recordings + dashboard.failedRecordings)
                                .firstOrNull { entry -> entry.uuid == selected.uuid }
                        },
                        error = result.takeIf { dashboard.warnings.isNotEmpty() }) }
                },
                onFailure = { error ->
                    val result = feedback.complete(ticket, OperationFeedback(
                        "refresh:${repository.source.id}", "dashboard", R.string.feedback_live_load_failed,
                        detail = feedbackFailureDetail(error), system = repository.source.system,
                        subject = repository.source.name, kind = NoticeKind.LIBRARY, delivery = FeedbackDelivery.INLINE,
                    )) ?: return@launch
                    refreshPolicy.completed(request)
                    refreshNeedsRetry = liveTvRefreshRetryable(error)
                    val offline = offlineNow()
                    _uiState.update { it.copy(offline = offline, canWake = offline && canWake(),
                        loading = false, refreshing = false, initialDashboardSettled = true, error = result) }
                },
            )
        }
    }

    fun schedule(event: TvhEpgEvent, series: Boolean = false) {
        if (!_uiState.value.capabilities.manageTimers || (series && !_uiState.value.capabilities.seriesTimers)) return
        val now = System.currentTimeMillis() / 1000
        val message = when {
            series -> R.string.tvh_series_timer_created
            event.isLive(now) -> R.string.tvh_recording_started
            else -> R.string.tvh_recording_scheduled
        }
        recordingAction("schedule:${event.eventId}", "event:${event.eventId}", event.title, message) {
            scheduleProgrammeRecording(repository, event, series, now)
        }
    }

    fun cancel(entry: TvhDvrEntry) {
        if (!_uiState.value.capabilities.manageTimers || !entry.canDelete) return
        val scope = _uiState.value.selectedEvent?.let { "event:${it.eventId}" } ?: "recording:${entry.uuid}"
        recordingAction("cancel:${entry.uuid}", scope, entry.title, if (entry.isActivelyRecording()) R.string.player_record_stopped else R.string.tvh_timer_cancelled,
            removeRecordingId = entry.uuid) { repository.cancelRecording(entry.uuid) }
    }

    fun delete(entry: TvhDvrEntry) {
        if (!_uiState.value.capabilities.deleteRecordings || !entry.canDelete) return
        recordingAction("delete:${entry.uuid}", "recording:${entry.uuid}", entry.title, R.string.tvh_recording_deleted,
            removeRecordingId = entry.uuid) { repository.deleteRecording(entry.uuid) }
    }

    fun updateTimer(entry: TvhDvrEntry, edit: LiveTvTimerEdit) {
        if (!_uiState.value.capabilities.editTimers) return
        recordingAction("updateTimer:${entry.uuid}", "edit", entry.title, R.string.tvh_timer_updated, closeEditor = true) {
            repository.updateTimer(entry.uuid, edit)
        }
    }

    fun updateSeriesTimer(entry: TvhAutoRec, edit: LiveTvTimerEdit) {
        if (!_uiState.value.capabilities.editTimers) return
        recordingAction("updateSeries:${entry.uuid}", "edit", entry.name, R.string.tvh_timer_updated, closeEditor = true) {
            repository.updateSeriesTimer(entry.uuid, edit)
        }
    }

    fun deleteAutoRec(entry: TvhAutoRec) {
        if (!_uiState.value.capabilities.manageTimers) return
        recordingAction("deleteAutoRec:${entry.uuid}", "timers", repository.source.name, R.string.tvh_series_timer_deleted,
            removeAutoRecId = entry.uuid) { repository.deleteAutoRecording(entry.uuid) }
    }

    private fun recordingAction(
        operation: String,
        scope: String,
        subject: String,
        successMessage: Int,
        removeRecordingId: String? = null,
        removeAutoRecId: String? = null,
        closeEditor: Boolean = false,
        action: suspend () -> Result<Unit>,
    ) {
        // Offline (cached data): record, timer and delete need the server.
        if (_uiState.value.actionInProgress || uiState.value.offline) return
        // Commit busy synchronously, so repeated OK cannot send the mutation twice.
        _uiState.update { it.copy(actionInProgress = true, actionFeedback = null, loading = false, refreshing = false) }
        refreshJob?.cancel()
        feedback.cancel("dashboard")
        val ticket = feedback.begin("recording")
        viewModelScope.launch {
            val result = suspendResult { action().getOrThrow() }
            currentCoroutineContext().ensureActive()
            val message = feedback.complete(ticket, OperationFeedback(
                "recording:${repository.source.id}:$operation", scope,
                if (result.isSuccess) successMessage else R.string.feedback_recording_failed,
                if (result.isSuccess) FeedbackPhase.SUCCESS else FeedbackPhase.ERROR,
                detail = result.exceptionOrNull()?.let(::feedbackFailureDetail),
                system = repository.source.system, subject = subject, kind = NoticeKind.RECORDING,
                delivery = FeedbackDelivery.ACTION,
            )) ?: return@launch
            if (result.isFailure) {
                _uiState.update { it.copy(actionInProgress = false, actionFeedback = message) }
                if (refreshPolicy.recoveryPending) load(force = false)
                return@launch
            }
            // Apply confirmed mutations immediately; a failed follow-up read must not erase lists.
            _uiState.update { state ->
                state.copy(
                    dashboard = state.dashboard?.let { dashboard -> dashboard.copy(
                        recordings = dashboard.recordings.filterNot { it.uuid == removeRecordingId },
                        failedRecordings = dashboard.failedRecordings.filterNot { it.uuid == removeRecordingId },
                        upcoming = dashboard.upcoming.filterNot { it.uuid == removeRecordingId },
                        autoRecs = dashboard.autoRecs.filterNot { it.uuid == removeAutoRecId },
                    ) },
                    selectedRecording = state.selectedRecording?.takeUnless { it.uuid == removeRecordingId },
                    editing = state.editing.takeUnless { closeEditor },
                    actionFeedback = null,
                )
            }
            _uiState.value.dashboard?.let(browseState::updateAfterMutation)
            // Reuse the ordinary load/error policy; success of the mutation remains success.
            _uiState.update { it.copy(actionInProgress = false) }
            load(force = false)
        }
    }

    fun currentEvent(channel: TvhChannel, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): TvhEpgEvent? =
        _uiState.value.dashboard?.events?.firstOrNull { it.channelUuid == channel.uuid && it.isLive(nowEpochSeconds) }

    fun nextEvent(channel: TvhChannel, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): TvhEpgEvent? =
        _uiState.value.dashboard?.events?.filter { it.channelUuid == channel.uuid && it.startEpochSeconds >= nowEpochSeconds }
            ?.minByOrNull { it.startEpochSeconds }

    fun timerFor(event: TvhEpgEvent): TvhDvrEntry? = _uiState.value.dashboard?.upcoming
        ?.let { findProgrammeRecording(event, it) }
}

