package com.ambient.tv.connectivity

import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.PlexRefreshBus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class UnreachableServerGateState(
    val unreachable: Set<String> = emptySet(),
    val pending: Boolean = false,
    val canDismiss: Boolean = false,
    val acknowledged: Set<String> = emptySet(),
    val names: List<String> = emptyList(),
    /** "Retry" is running: the dialog shows progress instead of looking dead. */
    val retrying: Boolean = false,
    /** No unreachable system can be woken (no Wake-on-LAN): retrying only checks again. */
    val wakeUnavailable: Boolean = false,
) {
    val hidden: Set<String> get() = hiddenUnreachableSystems(unreachable, acknowledged)
    /** Unacknowledged failures whose check has finished. */
    val prompt: Boolean get() = !pending && unreachable.any { it !in acknowledged }
}

/**
 * In-memory hide list for media servers that are not responding.
 * Saved server profiles are never removed; a server reappears when it is reachable again.
 */
@Singleton
class UnreachableServerGate @Inject constructor(
    private val preferences: AppPreferences,
    private val hosts: HostConnectivity,
    private val tokens: TokenStore,
    private val dlnaStore: DlnaStore,
    private val plex: ServerConnectionManager,
    private val jellyfin: JellyfinConnectionManager,
    private val emby: EmbyConnectionManager,
    private val resumeConnectivity: ResumeConnectivityMonitor,
    private val refreshBus: PlexRefreshBus,
    private val tvheadend: com.ambient.tv.server.TvheadendConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Keep Continue acknowledged for this app session, including checks and later outages.
    private val acknowledged = MutableStateFlow<Set<String>>(emptySet())
    private val revision = MutableStateFlow(0)
    private val retrying = MutableStateFlow(false)

    val state: StateFlow<UnreachableServerGateState> = combine(
        plex.state,
        jellyfin.state,
        emby.state,
        acknowledged,
        combine(revision, hosts.states, resumeConnectivity.status, tvheadend.state, retrying) { _, _, resumeStatus, _, _ -> resumeStatus },
    ) { plexState, jellyfinState, embyState, acknowledgedIds, resumeStatus ->
        snapshot(plexState, jellyfinState, embyState, acknowledgedIds, resumeStatus)
    }.stateIn(scope, SharingStarted.Eagerly, UnreachableServerGateState())

    init {
        scope.launch {
            hosts.states.map { states -> states.mapValues { it.value.health } }.distinctUntilChanged().collect {
                refresh()
                // Refresh actual service identities silently; never manufacture an authenticated state.
                coroutineScope {
                    if (hosts.health("plex") == HostHealth.ONLINE && plex.state.value !is ServerConnectionState.Online)
                        launch { if (plex.checkInBackground(force = true)) refreshBus.requestRefresh() }
                    if (hosts.health("jellyfin") == HostHealth.ONLINE && jellyfin.state.value !is ServerConnectionState.Online)
                        launch { if (jellyfin.checkInBackground(force = true)) refreshBus.requestRefresh() }
                    if (hosts.health("emby") == HostHealth.ONLINE && emby.state.value !is ServerConnectionState.Online)
                        launch { if (emby.checkInBackground(force = true)) refreshBus.requestRefresh() }
                    if (hosts.health("tvheadend") == HostHealth.ONLINE && tvheadend.state.value !is ServerConnectionState.Online)
                        launch { if (tvheadend.checkInBackground(force = true)) refreshBus.requestRefresh() }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(30_000L)
                val hidden = current().hidden
                coroutineScope {
                    if (MediaSystem.PLEX.key in hidden) launch { if (plex.checkInBackground()) refreshBus.requestRefresh() }
                    if (MediaSystem.JELLYFIN.key in hidden) launch { if (jellyfin.checkInBackground()) refreshBus.requestRefresh() }
                    if (MediaSystem.EMBY.key in hidden) launch { if (emby.checkInBackground()) refreshBus.requestRefresh() }
                    if (MediaSystem.TVHEADEND.key in hidden) launch { if (tvheadend.checkInBackground()) refreshBus.requestRefresh() }
                }
            }
        }
    }

    fun current(): UnreachableServerGateState = snapshot(
        plex.state.value,
        jellyfin.state.value,
        emby.state.value,
        acknowledged.value,
        resumeConnectivity.status.value,
    )

    /** Re-read enabled systems (YouTube and the rest are not connection-state flows). */
    fun refresh() {
        revision.value = revision.value + 1
    }

    /** Hides unreachable servers only when another system is active and reachable. */
    fun confirm() {
        val current = current()
        val next = acknowledgedAfterConfirm(current.acknowledged, current.toDecision())
        if (next != acknowledged.value) {
            if (MediaSystem.PLEX.key in current.unreachable) plex.pauseAutomaticReconnect()
            if (MediaSystem.JELLYFIN.key in current.unreachable) jellyfin.pauseAutomaticReconnect()
            if (MediaSystem.EMBY.key in current.unreachable) emby.pauseAutomaticReconnect()
            if (MediaSystem.TVHEADEND.key in current.unreachable) tvheadend.pauseAutomaticReconnect()
            acknowledged.value = next
        }
    }

    private var retryJob: kotlinx.coroutines.Job? = null

    fun retry(refreshBus: PlexRefreshBus) {
        if (retryJob?.isActive == true) return
        retrying.value = true
        retryJob = scope.launch {
          try {
            val failed = current().unreachable
            // One active recovery per machine. Siblings only refresh their own service identity.
            coroutineScope {
                failed.groupBy { hosts.machine(it) ?: it }.values.forEach { systems -> launch {
                    val first = systems.firstOrNull(::wakeConfigured) ?: systems.first()
                    when (first) {
                        "plex" -> plex.reconnect()
                        "jellyfin" -> jellyfin.reconnect()
                        "emby" -> emby.reconnect()
                        "tvheadend" -> tvheadend.reconnect()
                    }
                    systems.filter { it != first }.forEach { system -> when (system) {
                        "plex" -> plex.checkInBackground(force = true)
                        "jellyfin" -> jellyfin.checkInBackground(force = true)
                        "emby" -> emby.checkInBackground(force = true)
                        "tvheadend" -> tvheadend.checkInBackground(force = true)
                    } }
                } }
            }
            refreshBus.requestRefresh()
            refresh()
            // Say how it ended: without this, a failed retry just shows the same dialog again.
            val still = current().unreachable
            com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
                key = "unreachable:retry:${System.currentTimeMillis()}",
                message = if (still.isEmpty()) com.ambient.tv.R.string.unreachable_retry_success else com.ambient.tv.R.string.unreachable_retry_failed,
                args = listOf(still.map(::displayName).joinToString(", ")),
                kind = com.ambient.tv.notifications.NoticeKind.CONNECTION,
                phase = if (still.isEmpty()) com.ambient.tv.notifications.NoticePhase.SUCCESS else com.ambient.tv.notifications.NoticePhase.ERROR,
            ))
          } finally {
            retrying.value = false
          }
        }
    }

    /** A wake packet can be sent for [system]: Wake-on-LAN is on and has a MAC address. */
    private fun wakeConfigured(system: String): Boolean = when (system) {
        "plex" -> preferences.autoWakeEnabled && !preferences.macAddress.isNullOrBlank()
        "jellyfin" -> preferences.jellyfinAutoWakeEnabled && !preferences.jellyfinMacAddress.isNullOrBlank()
        "emby" -> preferences.embyAutoWakeEnabled && !preferences.embyMacAddress.isNullOrBlank()
        "tvheadend" -> preferences.tvheadendAutoWakeEnabled && !preferences.tvheadendMacAddress.isNullOrBlank()
        else -> false
    }

    private fun snapshot(
        plexState: ServerConnectionState,
        jellyfinState: ServerConnectionState,
        embyState: ServerConnectionState,
        acknowledgedIds: Set<String>,
        resumeStatus: ResumeConnectivityStatus,
    ): UnreachableServerGateState {
        val probes = mapOf(
            MediaSystem.PLEX.key to hostProbe("plex", preferences.isConfigured, plexState),
            MediaSystem.JELLYFIN.key to hostProbe("jellyfin", preferences.isJellyfinConfigured, jellyfinState),
            MediaSystem.EMBY.key to hostProbe("emby", preferences.isEmbyConfigured, embyState),
            MediaSystem.TVHEADEND.key to hostProbe("tvheadend",
                preferences.isTvheadendConfigured && !preferences.tvheadendLoginRejected, tvheadend.state.value),
        )
        val decision = decideUnreachableServers(
            UnreachableServerInput(probes, independentSystems()),
        )
        val names = decision.unreachable.map { displayName(it) }.filter { it.isNotBlank() }
        return UnreachableServerGateState(
            unreachable = decision.unreachable,
            // A short resume wake publishes WakeTimedOut before Home starts its full wake.
            // Keep that hand-off pending so the global dialog cannot flash between both jobs.
            pending = decision.pending || resumeHoldsUnreachablePrompt(resumeStatus),
            canDismiss = decision.canDismiss,
            acknowledged = acknowledgedIds,
            names = names,
            retrying = retrying.value,
            wakeUnavailable = decision.unreachable.isNotEmpty() && decision.unreachable.none(::wakeConfigured),
        )
    }

    private fun hostProbe(system: String, configured: Boolean, service: ServerConnectionState): ServerProbe {
        return hostAwareProbe(configured, service, hosts.health(system))
    }

    private fun independentSystems(): Set<String> = buildSet {
        if (preferences.youtubeEnabled) add(MediaSystem.YOUTUBE.key)
        if (preferences.spotifyEnabled) add(MediaSystem.SPOTIFY.key)
        if (preferences.trailersEnabled && !tokens.tmdbApiKey.isNullOrBlank()) add(MediaSystem.TRAILERS.key)
        if (preferences.filesEnabled && preferences.fileSources.any {
                it.kind == "local" || hosts.health("files:${it.id}") != HostHealth.OFFLINE
            }) add(MediaSystem.FILES.key)
        if (dlnaStore.servers().any { hosts.health("dlna:${it.id}") != HostHealth.OFFLINE }) add(MediaSystem.DLNA.key)
        // Another media server (TVHeadend included) counts only when its own probe is REACHABLE (see decideUnreachableServers).
    }

    private fun displayName(system: String): String = when (system) {
        MediaSystem.PLEX.key -> preferences.activeServerProfile()?.name?.takeIf { it.isNotBlank() }
            ?: preferences.host.takeIf { it.isNotBlank() }
            ?: "Plex"
        MediaSystem.JELLYFIN.key -> preferences.activeJellyfinServerProfile()?.name?.takeIf { it.isNotBlank() }
            ?: preferences.jellyfinHost.takeIf { it.isNotBlank() }
            ?: "Jellyfin"
        MediaSystem.EMBY.key -> preferences.activeEmbyServerProfile()?.name?.takeIf { it.isNotBlank() }
            ?: preferences.embyHost.takeIf { it.isNotBlank() }
            ?: "Emby"
        MediaSystem.TVHEADEND.key -> preferences.systemNames[MediaSystem.TVHEADEND.key]?.takeIf { it.isNotBlank() } ?: "TVHeadend"
        else -> system
    }
}

private fun UnreachableServerGateState.toDecision() = UnreachableServerDecision(
    unreachable = unreachable,
    pending = pending,
    canDismiss = canDismiss,
)
