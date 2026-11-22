package com.ambient.tv.connectivity

import android.os.SystemClock
import com.ambient.tv.data.browse.BrowseReconnects
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.wol.MachineWake
import com.ambient.tv.wol.ManualWakeRequest
import com.ambient.tv.wol.ManualWakeCoordinator
import com.ambient.tv.wol.WakeMachines
import com.ambient.tv.wol.canonicalWolMacKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Manual wake is independent of the startup/automatic WOL setting. */
internal fun canOfferOfflineWake(health: HostHealth, mac: String?): Boolean =
    health != HostHealth.ONLINE && canonicalWolMacKey(mac) != null

data class UnreachableServerGateState(
    val unreachable: Set<String> = emptySet(),
    val pending: Boolean = false,
    val canDismiss: Boolean = false,
    val names: List<String> = emptyList(),
    /** A wake/reconnect is running: the offline page shows progress instead of looking dead. */
    val retrying: Boolean = false,
    /** At least one configured media service is actively waking or waiting after WOL. */
    val waking: Boolean = false,
    /**
     * Servers shown as offline (see [nextOfflineSystems]). They stay in the menu and on Home as
     * an offline entry that offers to wake them; nothing is hidden or deleted.
     */
    val offline: Set<String> = emptySet(),
    /** No unreachable system can be woken (no Wake-on-LAN): retrying only checks again. */
    val wakeUnavailable: Boolean = false,
    /** Separate status for each offline system. */
    val wakeReady: Set<String> = emptySet(),
    val hostHealth: Map<String, HostHealth> = emptyMap(),
    val serverAddresses: Map<String, String> = emptyMap(),
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
    private val machines: WakeMachines,
    private val machineWake: MachineWake,
    private val browseReconnects: BrowseReconnects,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val revision = MutableStateFlow(0)
    private val retrying = MutableStateFlow(false)
    private val nextCheckAtElapsedMs = MutableStateFlow(0L)
    /** Deadline of the already-scheduled 30-second poll, using elapsed realtime. */
    val nextAutomaticCheckAtElapsedMs: StateFlow<Long> = nextCheckAtElapsedMs
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
                nextCheckAtElapsedMs.value = SystemClock.elapsedRealtime() + 30_000L
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

    private val retryLock = Any()
    private val retryJobs = mutableMapOf<String, Job>()

    /**
     * Cancel the real shared wake jobs rather than simply dismissing their progress view.
     * Only pause in-flight services; healthy providers must remain connected.
     */
    fun cancelStartupWake() {
        if (!current().waking) return
        synchronized(retryLock) { retryJobs.values.toList() }.forEach { it.cancel() }
        if (com.ambient.tv.server.isInFlightConnectState(plex.state.value)) plex.cancelStartupWake()
        if (com.ambient.tv.server.isInFlightConnectState(jellyfin.state.value)) jellyfin.cancelStartupWake()
        if (com.ambient.tv.server.isInFlightConnectState(emby.state.value)) emby.cancelStartupWake()
        if (com.ambient.tv.server.isInFlightConnectState(tvheadend.state.value)) tvheadend.cancelStartupWake()
        refresh()
    }

    /**
     * Cancel only the selected physical machine. Other servers may still be waking and must
     * keep their own connect jobs and notifications. Manual/NAS/DLNA jobs are tracked separately.
     */
    fun cancelMachineWake(machine: String): Boolean {
        val retryJob = synchronized(retryLock) { retryJobs[machine] }
        var cancelled = retryJob?.isActive == true
        retryJob?.cancel()
        if (hosts.machine("plex") == machine && com.ambient.tv.server.isInFlightConnectState(plex.state.value)) {
            plex.cancelStartupWake()
            cancelled = true
        }
        if (hosts.machine("jellyfin") == machine && com.ambient.tv.server.isInFlightConnectState(jellyfin.state.value)) {
            jellyfin.cancelStartupWake()
            cancelled = true
        }
        if (hosts.machine("emby") == machine && com.ambient.tv.server.isInFlightConnectState(emby.state.value)) {
            emby.cancelStartupWake()
            cancelled = true
        }
        if (hosts.machine("tvheadend") == machine && com.ambient.tv.server.isInFlightConnectState(tvheadend.state.value)) {
            tvheadend.cancelStartupWake()
            cancelled = true
        }
        if (cancelled) refresh()
        return cancelled
    }

    /** Retry/Reload re-enables wake attempts without changing persistent WOL preferences. */
    fun allowWakeRetry() {
        plex.allowWakeRetry()
        jellyfin.allowWakeRetry()
        emby.allowWakeRetry()
        tvheadend.allowWakeRetry()
    }

    fun retry() { startRetry(selected = null) }

    /**
     * Check the service first, then wake only a machine that is still unreachable. Manual WOL
     * stays independent of startup auto-WOL; offline siblings on the same machine follow it.
     */
    fun retrySystem(system: String) { startRetry(selected = setOf(system), manualWake = true) }

    /** Join the shared recovery without making a dismissed playback request its owner. */
    suspend fun retrySystemAndAwait(system: String) {
        startRetry(selected = setOf(system), manualWake = true).forEach { it.join() }
    }

    /** Called only on navigation into this offline provider, never during Home composition. */
    fun onOpenSystem(system: String) {
        if (system in preferences.wakeOnOpenSystems && system in current().offline) retrySystem(system)
    }

    private data class WakeTarget(val host: String, val port: Int, val mac: String?, val viaVpn: Boolean)

    /** A missing MAC on TVHeadend can reuse the valid MAC of Plex on the same host. */
    private fun manualWakeRequest(system: String): ManualWakeRequest? {
        val target = when (system) {
            "plex" -> WakeTarget(preferences.host, preferences.port,
                preferences.macAddress, preferences.wireGuardPlexEnabled)
            "jellyfin" -> WakeTarget(preferences.jellyfinHost, preferences.jellyfinPort,
                preferences.jellyfinMacAddress, preferences.wireGuardJellyfinEnabled)
            "emby" -> WakeTarget(preferences.embyHost, preferences.embyPort,
                preferences.embyMacAddress, preferences.wireGuardEmbyEnabled)
            "tvheadend" -> WakeTarget(preferences.tvheadendHost, preferences.tvheadendPort,
                preferences.tvheadendMacAddress, preferences.wireGuardTvheadendEnabled)
            else -> return null
        }
        if (target.host.isBlank() || target.port !in 1..65535) return null
        val groupMacs = machines.groupFor(system, target.host, target.mac).second
        val mac = canonicalWolMacKey(target.mac) ?: groupMacs.firstOrNull() ?: return null
        return ManualWakeRequest(system = system, subject = target.host, host = target.host,
            mac = mac, probePort = target.port, wireGuardHost = target.host.takeIf { target.viaVpn })
    }

    /** A single status action retries all affected services, without dropping later targets. */
    fun retrySystems(systems: Set<String>, includeMachineSiblings: Boolean = true) {
        startRetry(systems, includeMachineSiblings)
    }

    /** Actual service state wins over a retained status row or a delayed offline-page snapshot. */
    fun retryableSystems(): Set<String> = buildSet {
        fun addFailed(system: String, configured: Boolean, value: ServerConnectionState) {
            if (configured && value !is ServerConnectionState.Online &&
                value != ServerConnectionState.NotConfigured && !com.ambient.tv.server.isInFlightConnectState(value)) add(system)
        }
        addFailed("plex", preferences.hasPlexEndpoint, plex.state.value)
        addFailed("jellyfin", preferences.isJellyfinConfigured, jellyfin.state.value)
        addFailed("emby", preferences.isEmbyConfigured, emby.state.value)
        addFailed("tvheadend", preferences.hasTvheadendEndpoint, tvheadend.state.value)
    }

    private fun startRetry(
        selected: Set<String>?,
        includeMachineSiblings: Boolean = true,
        manualWake: Boolean = false,
    ): List<Job> {
        // A displayed action may outlive the offline snapshot. Explicit retry must still check
        // and reload an already-online service, or join its existing connect, instead of no-op.
        val offline = retryableSystems() + if (manualWake)
            selected.orEmpty().filter { retryEndpoint(it) != null } else emptySet()
        val failed = if (includeMachineSiblings) selectedOfflineRetryTargets(offline, selected, hosts::machine)
            else offline.intersect(selected.orEmpty())
        if (failed.isEmpty()) return emptyList()
        val scheduled = mutableListOf<Job>()
        val awaited = mutableListOf<Job>()
        synchronized(retryLock) {
            failed.groupBy { hosts.machine(it) ?: it }.forEach { (machine, systems) ->
                val previous = retryJobs[machine]
                if (previous != null && !previous.isCompleted && !previous.isCancelled) {
                    awaited += previous
                    return@forEach
                }
                val endpoints = systems.associateWith(::retryEndpoint)
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    // A cancelled attempt must finish its state cleanup before its replacement.
                    previous?.join()
                    val stillFailed = systems.filter {
                        endpoints[it] != null && endpoints[it] == retryEndpoint(it) &&
                            (manualWake || it in retryableSystems())
                    }
                    if (stillFailed.isEmpty()) return@launch
                    stillFailed.forEach(::allowWakeRetryFor)
                    // One recovery per machine. Siblings refresh their own service identity.
                    val first = if (manualWake) stillFailed.firstOrNull { it in selected.orEmpty() } ?: stillFailed.first()
                        else stillFailed.firstOrNull(::wakeConfigured) ?: stillFailed.first()
                    if (manualWake) {
                        val owner = currentCoroutineContext()[Job]
                        val isCurrent = { endpoints[first] == retryEndpoint(first) }
                        val request = manualWakeRequest(first)
                        retryOfflineService(
                            checkService = { checkService(first) },
                            machineOnline = { hosts.verifySystem(first) },
                            reconnect = { reconnectService(first) },
                            wake = request?.let { wakeRequest -> suspend {
                                val outcome = machineWake.wakeAndAwait(wakeRequest,
                                    recoveryInProgress = { serviceConnecting(first) },
                                    stillCurrent = { owner?.isActive == true && isCurrent() },
                                ) {
                                    // VPN setup may have taken time. Recheck the shared machine
                                    // and request ownership immediately before any magic packet.
                                    if (owner?.isActive != true || !isCurrent())
                                        throw CancellationException("Offline retry changed")
                                    val awake = hosts.verifySystem(first)
                                    currentCoroutineContext().ensureActive()
                                    if (!owner.isActive || !isCurrent())
                                        throw CancellationException("Offline retry changed")
                                    awake
                                }
                                outcome == ManualWakeCoordinator.Outcome.AWAKE ||
                                    (outcome == ManualWakeCoordinator.Outcome.ALREADY_WAKING && serviceConnecting(first))
                            } },
                            reload = { browseReconnects.request(first) },
                            stillCurrent = isCurrent,
                            recoveryInProgress = { serviceConnecting(first) },
                        )
                    } else {
                        reconnectService(first)
                    }
                    coroutineScope {
                        stillFailed.filter { it != first }.forEach { system -> launch {
                            if (endpoints[system] == retryEndpoint(system)) checkService(system,
                                retryForMs = if (hosts.health(system) == HostHealth.ONLINE)
                                    com.ambient.tv.server.CONNECT_RETRY_GRACE_MS else 0L)
                        } }
                    }
                    refresh()
                    // Each service already reports its result to the shared connection status.
                }
                retryJobs[machine] = job
                job.invokeOnCompletion {
                    synchronized(retryLock) {
                        if (retryJobs[machine] === job) retryJobs.remove(machine)
                        retrying.value = retryJobs.isNotEmpty()
                    }
                }
                scheduled += job
                awaited += job
            }
            retrying.value = retryJobs.isNotEmpty()
        }
        scheduled.forEach { it.start() }
        return awaited
    }

    /** Captured before probing: changing a profile must not wake or reload its replacement. */
    private fun retryEndpoint(system: String): String? = when (system) {
        "plex" -> if (preferences.hasPlexEndpoint)
            "${preferences.activeServerId}|${preferences.host}:${preferences.port}" else null
        "jellyfin" -> if (preferences.isJellyfinConfigured)
            listOf(preferences.activeJellyfinServerId, preferences.jellyfinHost, preferences.jellyfinPort,
                preferences.jellyfinUseHttps, preferences.jellyfinBasePath).joinToString("|") else null
        "emby" -> if (preferences.isEmbyConfigured)
            listOf(preferences.activeEmbyServerId, preferences.embyHost, preferences.embyPort,
                preferences.embyUseHttps, preferences.embyBasePath).joinToString("|") else null
        "tvheadend" -> if (preferences.hasTvheadendEndpoint)
            listOf(preferences.tvheadendHost, preferences.tvheadendPort,
                preferences.tvheadendUseHttps, preferences.tvheadendUsername).joinToString("|") else null
        else -> null
    }

    private fun serviceConnecting(system: String): Boolean = when (system) {
        "plex" -> plex.isConnecting()
        "jellyfin" -> jellyfin.isConnecting()
        "emby" -> emby.isConnecting()
        "tvheadend" -> tvheadend.isConnecting()
        else -> false
    }

    private suspend fun checkService(system: String, retryForMs: Long = 0L): Boolean = when (system) {
        "plex" -> plex.checkInBackground(force = true, retryForMs = retryForMs)
        "jellyfin" -> jellyfin.checkInBackground(force = true, retryForMs = retryForMs)
        "emby" -> emby.checkInBackground(force = true, retryForMs = retryForMs)
        "tvheadend" -> tvheadend.checkInBackground(force = true, retryForMs = retryForMs)
        else -> false
    }

    private suspend fun reconnectService(system: String): Boolean = when (system) {
        "plex" -> plex.reconnect()
        "jellyfin" -> jellyfin.reconnect()
        "emby" -> emby.reconnect()
        "tvheadend" -> tvheadend.reconnect()
        else -> false
    }

    private fun allowWakeRetryFor(system: String) = when (system) {
        "plex" -> plex.allowWakeRetry()
        "jellyfin" -> jellyfin.allowWakeRetry()
        "emby" -> emby.allowWakeRetry()
        "tvheadend" -> tvheadend.allowWakeRetry()
        else -> Unit
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
        val manualWakeReady = offlineNow.filterTo(mutableSetOf()) { system ->
            canOfferOfflineWake(hosts.health(system), manualWakeRequest(system)?.mac)
        }
        return UnreachableServerGateState(
            unreachable = decision.unreachable,
            pending = pending,
            canDismiss = decision.canDismiss,
            names = names,
            retrying = retrying.value,
            waking = listOf(plexState, jellyfinState, embyState, tvheadend.state.value).any {
                it == ServerConnectionState.SendingWakePacket || it is ServerConnectionState.Waking
            },
            offline = offlineNow,
            wakeUnavailable = offlineNow.isNotEmpty() && manualWakeReady.isEmpty(),
            wakeReady = manualWakeReady,
            hostHealth = offlineNow.associateWith { hosts.health(it) },
            serverAddresses = offlineNow.mapNotNull { system -> serverAddress(system)?.let { system to it } }.toMap(),
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

    /** Configured host name only: credentials and tokens are never shown. */
    private fun serverAddress(system: String): String? = when (system) {
        MediaSystem.PLEX.key -> preferences.host
        MediaSystem.JELLYFIN.key -> preferences.jellyfinHost
        MediaSystem.EMBY.key -> preferences.embyHost
        MediaSystem.TVHEADEND.key -> preferences.tvheadendHost
        else -> null
    }?.let { com.ambient.tv.network.NetworkEndpoint.normalizeHost(it) }?.takeIf(String::isNotBlank)

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
