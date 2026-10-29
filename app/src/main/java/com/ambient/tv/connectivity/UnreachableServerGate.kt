package com.ambient.tv.connectivity

import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.ui.common.MediaSystem
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
    val names: List<String> = emptyList(),
    /** A wake/reconnect is running: the offline page shows progress instead of looking dead. */
    val retrying: Boolean = false,
    /**
     * Servers shown as offline (see [nextOfflineSystems]). They stay in the menu and on Home as
     * an offline entry that offers to wake them; nothing is hidden or deleted.
     */
    val offline: Set<String> = emptySet(),
    /** No unreachable system can be woken (no Wake-on-LAN): retrying only checks again. */
    val wakeUnavailable: Boolean = false,
) {
}

/** Which media servers are not responding, and the one way to wake and reconnect them. */
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
    private val tvheadend: com.ambient.tv.server.TvheadendConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val revision = MutableStateFlow(0)
    private val retrying = MutableStateFlow(false)
    @Volatile private var offline = emptySet<String>()

    val state: StateFlow<UnreachableServerGateState> = combine(
        plex.state,
        jellyfin.state,
        emby.state,
        combine(revision, hosts.states, resumeConnectivity.status, tvheadend.state, retrying) { _, _, resumeStatus, _, _ -> resumeStatus },
    ) { plexState, jellyfinState, embyState, resumeStatus ->
        snapshot(plexState, jellyfinState, embyState, resumeStatus)
    }.stateIn(scope, SharingStarted.Eagerly, UnreachableServerGateState())

    init {
        scope.launch {
            var seenRecoveries = emptyMap<String, Long>()
            hosts.states.map { states -> states.mapValues { it.value.health to it.value.recoveries } }.distinctUntilChanged().collect { states ->
                val restored = states.filter { (machine, value) -> value.second > (seenRecoveries[machine] ?: 0L) }.keys
                seenRecoveries = states.mapValues { it.value.second }
                refresh()
                // A machine that just came up may still be starting its services (503, refused):
                // retry briefly instead of leaving them unreachable until the next 30 s round.
                val startGraceMs = com.ambient.tv.server.CONNECT_RETRY_GRACE_MS
                // Refresh actual service identities silently; never manufacture an authenticated state.
                coroutineScope {
                    if (hosts.health("plex") == HostHealth.ONLINE && (plex.state.value !is ServerConnectionState.Online || hosts.machine("plex") in restored))
                        launch { plex.checkInBackground(force = true, retryForMs = startGraceMs) }
                    if (hosts.health("jellyfin") == HostHealth.ONLINE && (jellyfin.state.value !is ServerConnectionState.Online || hosts.machine("jellyfin") in restored))
                        launch { jellyfin.checkInBackground(force = true, retryForMs = startGraceMs) }
                    if (hosts.health("emby") == HostHealth.ONLINE && (emby.state.value !is ServerConnectionState.Online || hosts.machine("emby") in restored))
                        launch { emby.checkInBackground(force = true, retryForMs = startGraceMs) }
                    if (hosts.health("tvheadend") == HostHealth.ONLINE && (tvheadend.state.value !is ServerConnectionState.Online || hosts.machine("tvheadend") in restored))
                        launch { tvheadend.checkInBackground(force = true, retryForMs = startGraceMs) }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(30_000L)
                val offline = current().offline
                coroutineScope {
                    if (MediaSystem.PLEX.key in offline) launch { plex.checkInBackground() }
                    if (MediaSystem.JELLYFIN.key in offline) launch { jellyfin.checkInBackground() }
                    if (MediaSystem.EMBY.key in offline) launch { emby.checkInBackground() }
                    if (MediaSystem.TVHEADEND.key in offline) launch { tvheadend.checkInBackground() }
                }
            }
        }
    }

    fun current(): UnreachableServerGateState = snapshot(
        plex.state.value,
        jellyfin.state.value,
        emby.state.value,
        resumeConnectivity.status.value,
    )

    /** Re-read enabled systems (YouTube and the rest are not connection-state flows). */
    fun refresh() {
        revision.value = revision.value + 1
    }

    private var retryJob: kotlinx.coroutines.Job? = null

    fun retry() {
        if (retryJob?.isActive == true) return
        retrying.value = true
        retryJob = scope.launch {
          try {
            val failed = current().offline
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
        // A short resume wake publishes WakeTimedOut before Home starts its full wake.
        // Keep that hand-off pending so nothing is marked offline between both jobs.
        val pending = decision.pending || resumeHoldsUnreachablePrompt(resumeStatus)
        val offlineNow = nextOfflineSystems(offline, probes, settled = !pending).also { offline = it }
        return UnreachableServerGateState(
            unreachable = decision.unreachable,
            pending = pending,
            canDismiss = decision.canDismiss,
            names = names,
            retrying = retrying.value,
            offline = offlineNow,
            wakeUnavailable = offlineNow.isNotEmpty() && offlineNow.none(::wakeConfigured),
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
