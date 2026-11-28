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
    /** Metadata may be browsed before this visit has received a fresh source response. */
    val savedView: Boolean = true,
    /** The selected service failed even though another port on its machine may still answer. */
    val serviceUnconfirmed: Boolean = false,
    val lastUpdatedEpochMillis: Long? = null,
    /** Offline TVHeadend with a MAC address: "wake server" is offered. */
    val canWake: Boolean = false,
    val waking: Boolean = false,
    val guideOptions: GuideOptions = GuideOptions(),
    val source: LiveTvSource = LiveTvSource("tvheadend", "tvheadend", "TVHeadend"),
    val capabilities: LiveTvCapabilities = LiveTvCapabilities(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val tab: TvheadendTab = TvheadendTab.LIVE,
    val dashboard: TvhDashboard? = null,
    val selectedEvent: TvhEpgEvent? = null,
    val selectedRecording: TvhDvrEntry? = null,
    /** Timer or series timer whose settings dialog is open. */
    val editing: LiveTvEditTarget? = null,
    val actionInProgress: Boolean = false,
    val actionFeedback: OperationFeedback? = null,
    val error: OperationFeedback? = null,
    /** Cached or channels-only snapshots do not prove the initial optional sections are empty. */
    val initialDashboardSettled: Boolean = false,
    val playbackReturnRevision: Long = 0L,
    val hbbtvPending: Boolean = false,
)

@HiltViewModel
class TvheadendViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val registry: LiveTvRegistry,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val snapshots: com.ambient.tv.data.browse.DisplaySnapshots,
    val availability: com.ambient.tv.data.browse.SourceAvailability,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val reminderStore: com.ambient.tv.livetv.LiveTvReminderStore,
    private val playbackAvailability: com.ambient.tv.data.browse.PlaybackAvailability,
    private val artwork: LiveTvArtworkRepository,
) : ViewModel() {
    val reminders = reminderStore.reminders.also { reminderStore.refresh() }

    /** Remind before the programme starts (any live source; works without recording rights). */
    fun toggleReminder(event: com.ambient.tv.tvheadend.domain.TvhEpgEvent) {
        val added = reminderStore.toggle(com.ambient.tv.livetv.LiveTvReminder(
            sourceId = repository.source.id, channelUuid = event.channelUuid, channelName = event.channelName,
            eventId = event.eventId, title = event.title, startEpochSeconds = event.startEpochSeconds,
            stopEpochSeconds = event.stopEpochSeconds, imageUrl = event.imageUrl,
        ))
        com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
            "reminder:${event.eventId}", if (added) com.ambient.tv.R.string.reminder_set else com.ambient.tv.R.string.reminder_removed,
            subject = event.title, kind = com.ambient.tv.notifications.NoticeKind.LIBRARY, interruptPlayback = false,
        ))
    }
    val feedback = OperationFeedbackReporter()
    private var repository = registry.repository(savedStateHandle.get<String>("sourceId"))
    private val hostSource get() = liveTvHostSource(repository.source.id, repository.source.system)
    private fun offlineNow() = liveTvSourceOffline(availability.hasNetwork(), hosts.health(hostSource))
    // Mutations cannot wait for combine/stateIn to publish a just-observed HTTP failure.
    private fun connectionRequiredNow() = _uiState.value.connectionRequired || offlineNow() || availability.browseUnconfirmed(hostSource)
    val browseState get() = registry.browseState(repository)
    private val _uiState = MutableStateFlow(TvheadendUiState(
        configured = true, source = repository.source,
        offline = offlineNow(), canWake = offlineNow() && canWake(),
        guideOptions = preferences.guideOptions(repository.source.id),
        dashboard = browseState.dashboard, capabilities = browseState.capabilities,
        savedView = browseState.dashboard == null || browseState.savedView, initialDashboardSettled = browseState.dashboard != null && !browseState.savedView,
        loading = browseState.dashboard == null,
        tab = TvheadendTab.entries.firstOrNull {
            it.name.equals(savedStateHandle.get<String>("tab"), ignoreCase = true)
        } ?: TvheadendTab.LIVE,
    ))
    val uiState: StateFlow<TvheadendUiState> = kotlinx.coroutines.flow.combine(
        _uiState, hosts.states.map { hosts.health(hostSource) }.distinctUntilChanged(), availability.changes,
    ) { state, health, _ ->
        val offline = liveTvSourceOffline(availability.hasNetwork(), health)
        state.copy(offline = offline, canWake = offline && canWake(),
            serviceUnconfirmed = availability.browseShowsOffline(hostSource))
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _uiState.value)

    private val isTvheadend get() = repository.source.system == "tvheadend"
    private fun canWake() = isTvheadend && !preferences.tvheadendMacAddress.isNullOrBlank()
    private var connectionJob: Job? = null
    private var connectionRevision = 0L

    /** Explicit retry verifies this exact live source before considering shared machine WOL. */
    fun retryConnection() {
        if (connectionJob?.isActive == true) return
        val requestedRepository = repository
        val requestedIdentity = registry.displayCacheIdentity(requestedRepository)
            ?: run { load(force = false); return }
        val revision = ++connectionRevision
        _uiState.update { it.copy(waking = true) }
        connectionJob = viewModelScope.launch {
            try {
                val result = playbackAvailability.retryLive(requestedRepository.source.id)
                currentCoroutineContext().ensureActive()
                if (repository !== requestedRepository || registry.displayCacheIdentity(requestedRepository) != requestedIdentity) return@launch
                if (result.allowed) load(force = true)
                else _uiState.update { it.copy(savedView = true, initialDashboardSettled = true) }
            } finally {
                if (revision == connectionRevision) _uiState.update { it.copy(waking = false) }
            }
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

    // uiState combines availability asynchronously. The return preview/commit must keep one
    // synchronous layout revision even while that combined stream catches up.
    val playbackReturnRevision: Long get() = _uiState.value.playbackReturnRevision

    fun commitReturnFocus(revision: Long, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        LiveTvReturnFocus.commit(repository.source.id, revision) { returned ->
            val state = _uiState.value
            returnFocusSelections(returned.channelUuid, state.dashboard, state.guideOptions, nowEpochSeconds)
                ?.forEach { (tab, target) -> target.contentId?.let { onContentFocused(tab, it, target.rowId) } }
            _uiState.update { it.copy(playbackReturnRevision = revision) }
        }

    private var preparedTab: TvheadendTab? = null

    /** Apply a destination's tab once; returning must keep the current tab and open selection. */
    fun prepareTab(tab: TvheadendTab) {
        if (preparedTab == tab) return
        preparedTab = tab
        if (_uiState.value.tab != tab) selectTab(tab)
    }

    fun selectTab(tab: TvheadendTab) {
        _uiState.update { it.copy(tab = tab, selectedEvent = null, selectedRecording = null, editing = null, actionFeedback = null) }
        if (tab == TvheadendTab.HBBTV) requestHbbtv()
    }
    fun setGuideOptions(options: GuideOptions) {
        preferences.setGuideOptions(repository.source.id, options)
        _uiState.update { it.copy(guideOptions = options) }
    }

    fun selectEvent(event: TvhEpgEvent?) = _uiState.update { it.copy(selectedEvent = event, selectedRecording = null, actionFeedback = null) }
    fun selectRecording(entry: TvhDvrEntry?) = _uiState.update { it.copy(selectedRecording = entry, selectedEvent = null, actionFeedback = null) }
    fun edit(target: LiveTvEditTarget?) {
        if (target != null && (!_uiState.value.capabilities.editTimers || connectionRequiredNow())) return
        _uiState.update { it.copy(editing = target, actionFeedback = null) }
    }

    /** Shared with the player; only composed cards and opened details request their own art. */
    val titleArt = artwork.titleArt
    fun requestTitleArt(titles: Collection<String>, scope: kotlinx.coroutines.CoroutineScope = viewModelScope) =
        artwork.requestTitleArt(titles, scope)

    private var refreshJob: Job? = null
    private var actionJob: Job? = null
    private val refreshPolicy = LiveTvRefreshPolicy()
    private var refreshNeedsRetry = false
    private var hbbtvObserver: Job? = null

    init { observeHbbtvMetadata() }

    private fun observeHbbtvMetadata() {
        hbbtvObserver?.cancel()
        val requested = repository as? TvheadendLiveTvRepository ?: return
        hbbtvObserver = viewModelScope.launch {
            suspendResult { requested.restoreCachedHbbtv() }
            if (repository === requested && registry.displayCacheIdentity(requested) != null)
                requested.requestHbbtvRefresh()
            requested.hbbtvChanges.collect {
                if (repository !== requested || registry.displayCacheIdentity(requested) == null) return@collect
                browseState.updateChannelMetadata(requested::enrichHbbtvChannels)
                _uiState.update { state ->
                    val dashboard = state.dashboard?.let { old ->
                        val channels = requested.enrichHbbtvChannels(old.channels)
                        if (channels === old.channels) old else old.copy(channels = channels)
                    }
                    state.copy(dashboard = dashboard, hbbtvPending = requested.hbbtvPending())
                }
            }
        }
    }

    private fun requestHbbtv(force: Boolean = false) {
        val current = repository as? TvheadendLiveTvRepository ?: return
        if (registry.displayCacheIdentity(current) != null) current.requestHbbtvRefresh(force)
    }

    fun retryOffline() {
        // connectionRequiredNow() also covers a read pending after a network switch (not shown).
        if ((connectionRequiredNow() || refreshNeedsRetry) && availability.hasNetwork() &&
            refreshJob?.isActive != true && connectionJob?.isActive != true)
            load(force = true, announceSuccess = false)
    }

    fun refresh() {
        if (_uiState.value.actionInProgress) return
        if (connectionRequiredNow()) retryConnection() else {
            requestHbbtv(force = true)
            load(force = true)
        }
    }

    /** Navigation reuses the current snapshot. Only a new recovery revision forces a read. */
    fun refreshOnEntry(revision: Long) {
        val current = registry.configured().firstOrNull { it.source.id == repository.source.id }
        if (current !== repository) { load(force = false); return }
        requestHbbtv()
        val recovered = browseState.observeRecovery(revision)
        if (recovered) refreshPolicy.reconnected()
        if (!recovered && refreshJob?.isActive == true) return
        if (!_uiState.value.actionInProgress && refreshJob?.isActive != true) {
            val shared = browseState.dashboard
            if (shared != null && shared !== _uiState.value.dashboard) {
                _uiState.update { it.copy(dashboard = shared, capabilities = browseState.capabilities,
                    savedView = browseState.savedView, loading = false, initialDashboardSettled = !browseState.savedView) }
            }
        }
        if (!recovered && !refreshPolicy.recoveryPending && !refreshNeedsRetry &&
            !connectionRequiredNow() && !browseState.needsRefresh() && _uiState.value.dashboard != null) return
        load(force = false)
    }

    /** The visible guide catches up with the rolling index without a navigation or forced reload. */
    fun refreshVisibleIfIdle() {
        if (com.ambient.tv.data.browse.BrowseWorkBudget.idle()) requestHbbtv()
        if (com.ambient.tv.data.browse.BrowseWorkBudget.idle() && !connectionRequiredNow() &&
            !_uiState.value.actionInProgress && refreshJob?.isActive != true &&
            connectionJob?.isActive != true && browseState.needsRefresh()) load(force = false)
    }

    private fun load(force: Boolean, announceSuccess: Boolean = force) {
        val currentRepository = registry.configured().firstOrNull { it.source.id == repository.source.id }
        if (currentRepository == null) {
            connectionRevision++
            connectionJob?.cancel()
            actionJob?.cancel()
            feedback.cancel("recording")
            refreshJob?.cancel()
            hbbtvObserver?.cancel()
            _uiState.update { it.copy(configured = false, hbbtvPending = false, dashboard = null, selectedEvent = null,
                selectedRecording = null, editing = null, actionInProgress = false, waking = false, loading = false, refreshing = false) }
            return
        }
        if (currentRepository !== repository) {
            connectionRevision++
            connectionJob?.cancel()
            actionJob?.cancel()
            feedback.cancel("recording")
            refreshJob?.cancel()
            repository = currentRepository
            _uiState.update { TvheadendUiState(configured = true, source = repository.source, tab = it.tab,
                guideOptions = it.guideOptions, loading = true, capabilities = browseState.capabilities) }
            observeHbbtvMetadata()
        }
        val request = refreshPolicy.request(force, announceSuccess, _uiState.value.actionInProgress) ?: return
        refreshJob?.cancel()
        val ticket = feedback.begin("dashboard")
        _uiState.update { it.copy(loading = it.dashboard == null, refreshing = it.dashboard != null, error = null) }
        refreshJob = viewModelScope.launch {
            val requestedRepository = repository
            fun stillCurrent(): Boolean = repository === requestedRepository && registry.displayCacheIdentity(requestedRepository) != null
            val owner = "livetv:${requestedRepository.source.id}"
            val namespace = liveTvDisplayNamespace(snapshots, requestedRepository, registry)
                ?: run { load(force = false); return@launch }
            if (_uiState.value.dashboard == null) {
            (requestedRepository as? TvheadendLiveTvRepository)?.let { suspendResult { it.restoreCachedHbbtv() } }
            val savedAt = snapshots.read<Long>(namespace, "updated_at")
            if (!stillCurrent()) { load(force = false); return@launch }
            val savedCapabilities = snapshots.read<LiveTvCapabilities>(namespace, "capabilities")
            val rawDashboard = snapshots.read<LiveTvDashboard>(namespace, "dashboard")
                ?: (snapshots.read<List<LiveTvChannel>>(namespace, "channels") ?: suspendResult { requestedRepository.cachedChannels() }.getOrNull())?.let { channels ->
                    LiveTvDashboard(LiveTvServerInfo(requestedRepository.source.name, null, null),
                        channels, emptyList(), emptyList(), emptyList())
                }
            val savedDashboard = rawDashboard?.let { dashboard ->
                val start = System.currentTimeMillis() / 1000L - 3600L
                val guide = suspendResult { requestedRepository.cachedGuideEvents(start, 12) }.getOrNull()
                val channels = (requestedRepository as? TvheadendLiveTvRepository)?.enrichHbbtvChannels(dashboard.channels)
                    ?: dashboard.channels
                dashboard.copy(events = guide ?: dashboard.events, channels = channels)
            }
            savedDashboard?.let { cached ->
                if (!stillCurrent()) { load(force = false); return@launch }
                if (_uiState.value.dashboard == null) _uiState.update { it.copy(dashboard = cached, offline = offlineNow(), canWake = offlineNow() && canWake(),
                    capabilities = savedCapabilities ?: it.capabilities.copy(available = true,
                        guide = isTvheadend || cached.events.isNotEmpty(), recordings = isTvheadend || cached.recordings.isNotEmpty(),
                        timers = isTvheadend || cached.upcoming.isNotEmpty() || cached.autoRecs.isNotEmpty()),
                    lastUpdatedEpochMillis = savedAt, savedView = true, loading = false, refreshing = true) }
                // A different retained destination may already have completed a network refresh.
                if (browseState.dashboard == null) browseState.update(cached, _uiState.value.capabilities, savedView = true)
            }
            }
            if (!stillCurrent()) { load(force = false); return@launch }
            _uiState.update { it.copy(loading = it.dashboard == null, refreshing = it.dashboard != null, error = null) }
            val accessSource = liveTvHostSource(requestedRepository.source.id, requestedRepository.source.system)
            val forceRead = request.force || availability.browseUnconfirmed(accessSource)
            val accessGeneration = availability.accessGeneration(accessSource)
            // Only this load's own reads decide whether the view shows stored data. A global
            // "served cache" flag from Home or an earlier read made the status flip back and forth.
            val cacheMarker = requestedRepository.cacheFallbackMarker
            suspendResult {
                var prefetch = requestedRepository.prefetchDashboard(forceRead)
                val caps = prefetch.capabilities
                currentCoroutineContext().ensureActive()
                check(stillCurrent()) { "Live TV source configuration changed" }
                _uiState.update { it.copy(capabilities = caps) }
                if (caps.available && _uiState.value.dashboard == null) {
                    val channels = prefetch.channels ?: requestedRepository.channels(forceRead)
                    prefetch = prefetch.copy(channels = channels)
                    currentCoroutineContext().ensureActive()
                    check(stillCurrent()) { "Live TV source configuration changed" }
                    _uiState.update { it.copy(loading = false, refreshing = true, dashboard = LiveTvDashboard(
                        LiveTvServerInfo(repository.source.name, null, null), channels, emptyList(), emptyList(), emptyList())) }
                }
                requestedRepository.dashboard(force = forceRead, prefetch = prefetch)
            }.also { currentCoroutineContext().ensureActive() }.fold(
                onSuccess = { fresh ->
                    if (!stillCurrent()) { load(force = false); return@launch }
                    val old = _uiState.value.dashboard
                    val merge = LiveTvDashboardMergePolicy(fresh.warnings)
                    val merged = if (fresh.warnings.isEmpty() || old == null) fresh else fresh.copy(
                        events = merge.merge(LiveTvDashboardPart.EPG, fresh.events, old.events),
                        recordings = merge.merge(LiveTvDashboardPart.RECORDINGS, fresh.recordings, old.recordings),
                        upcoming = merge.merge(LiveTvDashboardPart.TIMERS, fresh.upcoming, old.upcoming),
                        failedRecordings = merge.merge(LiveTvDashboardPart.FAILED_RECORDINGS, fresh.failedRecordings, old.failedRecordings),
                        autoRecs = merge.merge(LiveTvDashboardPart.SERIES_TIMERS, fresh.autoRecs, old.autoRecs),
                    )
                    val compared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        merged.copy(channels = old?.channels?.takeIf { it == merged.channels } ?: merged.channels,
                            events = old?.events?.takeIf { it == merged.events } ?: merged.events)
                    }
                    if (!stillCurrent()) { load(force = false); return@launch }
                    val hbbtv = requestedRepository as? TvheadendLiveTvRepository
                    val enriched = hbbtv?.enrichHbbtvChannels(compared.channels) ?: compared.channels
                    val dashboard = if (enriched === compared.channels) compared else compared.copy(channels = enriched)
                    val servedCache = requestedRepository.servedCacheSince(cacheMarker)
                    if (!servedCache && fresh.events.isNotEmpty()) reminderStore.reconcileGuide(requestedRepository.source.id, fresh.events)
                    if (forceRead && !servedCache && fresh.warnings.isEmpty())
                        availability.confirmBrowseRead(accessSource, accessGeneration)
                    val updatedAt = if (!servedCache) System.currentTimeMillis() else _uiState.value.lastUpdatedEpochMillis
                    if (fresh.warnings.isEmpty() && !servedCache) {
                        snapshots.write(owner, namespace, "dashboard", dashboard.copy(
                        events = if (isTvheadend) emptyList() else dashboard.events,
                        recordings = dashboard.recordings.map { it.copy(url = null) },
                        upcoming = dashboard.upcoming.map { it.copy(url = null) },
                        failedRecordings = dashboard.failedRecordings.map { it.copy(url = null) },
                        ))
                        snapshots.write(owner, namespace, "updated_at", checkNotNull(updatedAt))
                        snapshots.write(owner, namespace, "capabilities", _uiState.value.capabilities)
                    }
                    browseState.update(dashboard, _uiState.value.capabilities, savedView = servedCache)
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
                    refreshNeedsRetry = servedCache
                    val offline = offlineNow()
                    _uiState.update { it.copy(configured = true, offline = offline, canWake = offline && canWake(),
                        savedView = servedCache, lastUpdatedEpochMillis = updatedAt,
                        loading = false, refreshing = false, initialDashboardSettled = true,
                        hbbtvPending = hbbtv?.hbbtvPending() == true,
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
                    if (!stillCurrent()) { load(force = false); return@launch }
                    val result = feedback.complete(ticket, OperationFeedback(
                        "refresh:${repository.source.id}", "dashboard", R.string.feedback_live_load_failed,
                        detail = feedbackFailureDetail(error), system = repository.source.system,
                        subject = repository.source.name, kind = NoticeKind.LIBRARY, delivery = FeedbackDelivery.INLINE,
                    )) ?: return@launch
                    refreshPolicy.completed(request)
                    refreshNeedsRetry = liveTvRefreshRetryable(error)
                    val offline = offlineNow()
                    _uiState.update { it.copy(offline = offline, canWake = offline && canWake(),
                        savedView = true, loading = false, refreshing = false, initialDashboardSettled = true, error = result) }
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
            scheduleProgrammeRecording(it, event, series, now)
        }
    }

    fun cancel(entry: TvhDvrEntry) {
        if (!_uiState.value.capabilities.manageTimers || !entry.canDelete) return
        val scope = _uiState.value.selectedEvent?.let { "event:${it.eventId}" } ?: "recording:${entry.uuid}"
        recordingAction("cancel:${entry.uuid}", scope, entry.title, if (entry.isActivelyRecording()) R.string.player_record_stopped else R.string.tvh_timer_cancelled,
            removeRecordingId = entry.uuid) { it.cancelRecording(entry.uuid) }
    }

    fun delete(entry: TvhDvrEntry) {
        if (!_uiState.value.capabilities.deleteRecordings || !entry.canDelete) return
        recordingAction("delete:${entry.uuid}", "recording:${entry.uuid}", entry.title, R.string.tvh_recording_deleted,
            removeRecordingId = entry.uuid) { it.deleteRecording(entry.uuid) }
    }

    fun updateTimer(entry: TvhDvrEntry, edit: LiveTvTimerEdit) {
        if (!_uiState.value.capabilities.editTimers) return
        recordingAction("updateTimer:${entry.uuid}", "edit", entry.title, R.string.tvh_timer_updated, closeEditor = true) {
            it.updateTimer(entry.uuid, edit)
        }
    }

    fun updateSeriesTimer(entry: TvhAutoRec, edit: LiveTvTimerEdit) {
        if (!_uiState.value.capabilities.editTimers) return
        recordingAction("updateSeries:${entry.uuid}", "edit", entry.name, R.string.tvh_timer_updated, closeEditor = true) {
            it.updateSeriesTimer(entry.uuid, edit)
        }
    }

    fun deleteAutoRec(entry: TvhAutoRec) {
        if (!_uiState.value.capabilities.manageTimers) return
        recordingAction("deleteAutoRec:${entry.uuid}", "timers", repository.source.name, R.string.tvh_series_timer_deleted,
            removeAutoRecId = entry.uuid) { it.deleteAutoRecording(entry.uuid) }
    }

    private fun recordingAction(
        operation: String,
        scope: String,
        subject: String,
        successMessage: Int,
        removeRecordingId: String? = null,
        removeAutoRecId: String? = null,
        closeEditor: Boolean = false,
        action: suspend (LiveTvRepository) -> Result<Unit>,
    ) {
        // Offline (cached data): record, timer and delete need the server.
        if (_uiState.value.actionInProgress || connectionRequiredNow()) return
        val requestedRepository = repository
        val requestedIdentity = registry.displayCacheIdentity(requestedRepository)
            ?: run { load(force = false); return }
        fun stillCurrent() = repository === requestedRepository &&
            registry.displayCacheIdentity(requestedRepository) == requestedIdentity
        // Commit busy synchronously, so repeated OK cannot send the mutation twice.
        _uiState.update { it.copy(actionInProgress = true, actionFeedback = null, loading = false, refreshing = false) }
        refreshJob?.cancel()
        feedback.cancel("dashboard")
        val ticket = feedback.begin("recording")
        actionJob = viewModelScope.launch {
            if (!stillCurrent()) { load(force = false); return@launch }
            if (connectionRequiredNow()) {
                feedback.cancel(ticket)
                _uiState.update { it.copy(actionInProgress = false, savedView = true) }
                return@launch
            }
            val result = suspendResult { action(requestedRepository).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) { load(force = false); return@launch }
            val message = feedback.complete(ticket, OperationFeedback(
                "recording:${requestedRepository.source.id}:$operation", scope,
                if (result.isSuccess) successMessage else R.string.feedback_recording_failed,
                if (result.isSuccess) FeedbackPhase.SUCCESS else FeedbackPhase.ERROR,
                detail = if (result.isFailure) R.string.offline_browse_action_unconfirmed else null,
                system = repository.source.system, subject = subject, kind = NoticeKind.RECORDING,
                delivery = FeedbackDelivery.ACTION,
            )) ?: return@launch
            if (result.isFailure) {
                // The write may have reached the server before its response was lost. Keep the
                // shown data, lock further mutations, and only re-read on recovery; never replay it.
                availability.accessFailed(liveTvHostSource(requestedRepository.source.id, requestedRepository.source.system))
                refreshNeedsRetry = true
                _uiState.update { it.afterUnconfirmedRecordingAction(message) }
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

