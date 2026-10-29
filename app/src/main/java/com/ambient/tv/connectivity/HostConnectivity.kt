package com.ambient.tv.connectivity

import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.dlna.dlnaWakeEndpoint
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.wol.WakeMachines
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private data class PlaybackHostSignal(val source: String?, val id: String?, val playing: Boolean, val error: String?)

/** Shared reachability evidence. Probes never establish VPN routes or send wake packets. */
@Singleton
class HostConnectivity internal constructor(
    private val preferences: AppPreferences,
    private val dlna: DlnaStore,
    private val machines: WakeMachines,
    /** True for a host routed through WireGuard while the tunnel is not up. */
    private val tunnelDown: (host: String) -> Boolean = { false },
    /** True for a host routed through WireGuard: its probe gets a tunnel round trip of time. */
    private val tunnelled: (host: String) -> Boolean = { false },
) {
    @Inject constructor(
        preferences: AppPreferences,
        dlna: DlnaStore,
        machines: WakeMachines,
        wireGuard: javax.inject.Provider<com.ambient.tv.vpn.WireGuardRouteController>,
    ) : this(preferences, dlna, machines, { host ->
        val route = wireGuard.get()
        route.status.value !is com.ambient.tv.vpn.WireGuardRouteState.Up &&
            route.tunnelHosts().any { it.equals(host, ignoreCase = true) }
    }, { host -> wireGuard.get().tunnelHosts().any { it.equals(host, ignoreCase = true) } })

    private val store = HostHealthStore()
    val states = store.states
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checks = ConcurrentHashMap<String, Mutex>()
    private var started = false

    internal fun endpoints(): List<HostEndpoint> = buildList {
        fun addEndpoint(system: String, host: String, port: Int, mac: String?) {
            val normalized = NetworkEndpoint.normalizeHost(host)
            if (normalized.isNotBlank() && port in 1..65535)
                add(HostEndpoint(system, normalized, port, machines.keyFor(system, host, mac)))
        }
        if (preferences.hasPlexEndpoint) addEndpoint("plex", preferences.host, preferences.port, preferences.macAddress)
        if (preferences.jellyfinEnabled && preferences.jellyfinHost.isNotBlank()) addEndpoint("jellyfin", preferences.jellyfinHost, preferences.jellyfinPort, preferences.jellyfinMacAddress)
        if (preferences.embyEnabled && preferences.embyHost.isNotBlank()) addEndpoint("emby", preferences.embyHost, preferences.embyPort, preferences.embyMacAddress)
        if (preferences.hasTvheadendEndpoint) addEndpoint("tvheadend", preferences.tvheadendHost, preferences.tvheadendPort, preferences.tvheadendMacAddress)
        // Live TV keeps its captured profile while another server is selected for normal browsing.
        if (preferences.plexEnabled) preferences.serverProfiles.forEach { profile ->
            mediaServerProfileSourceId("plex", profile.id)?.let {
                addEndpoint(it, profile.host, profile.port, profile.macAddress)
            }
        }
        if (preferences.jellyfinEnabled) preferences.jellyfinServerProfiles.filter { it.authenticated }.forEach { profile ->
            mediaServerProfileSourceId("jellyfin", profile.id, profile.userId)?.let {
                addEndpoint(it, profile.host, profile.port, profile.macAddress)
            }
        }
        if (preferences.embyEnabled) preferences.embyServerProfiles.filter { it.authenticated }.forEach { profile ->
            mediaServerProfileSourceId("emby", profile.id, profile.userId)?.let {
                addEndpoint(it, profile.host, profile.port, profile.macAddress)
            }
        }
        preferences.fileSources.filter { it.kind != "local" }.forEach { addEndpoint("files:${it.id}", it.host, it.port, it.wolMac) }
        dlna.servers().forEach { server -> dlnaWakeEndpoint(server)?.let { (host, port) -> addEndpoint("dlna:${server.id}", host, port, server.wolMac) } }
    }

    fun machine(system: String): String? = endpoints().firstOrNull { it.system == system }?.machine
    fun begin(system: String): HostCheck? = machine(system)?.let(store::begin)
    fun beginEndpoint(system: String, host: String, port: Int): HostCheck? =
        matchingHostEndpoint(endpoints(), host, port, system)
        ?.let { store.begin(it.machine) }
    fun nextCheck(previous: HostCheck?): HostCheck? = previous?.let { store.begin(it.machine) }
    fun health(system: String): HostHealth = machine(system)?.let { store.current(it).health } ?: HostHealth.UNKNOWN
    fun online(check: HostCheck?) { if (check != null) store.online(check.machine) }
    fun interceptor() = okhttp3.Interceptor { chain ->
        val url = chain.request().url
        val target = matchingHostEndpoint(endpoints(), url.host, url.port)
        val check = target?.let { store.begin(it.machine) }
        try {
            chain.proceed(chain.request()).also { if (it.networkResponse != null) online(check) }
        } catch (error: java.io.IOException) {
            if (!chain.call().isCanceled()) failure(check)
            throw error
        }
    }

    /** Fresh passive evidence before an active recovery decides whether to send wake packets. */
    suspend fun verifySystem(system: String): Boolean = begin(system)?.let { verify(it) } ?: false

    fun failure(check: HostCheck?) {
        if (check != null) scope.launch { verify(check) }
    }

    /** All configured ports are checked before propagating an outage to sibling services. */
    suspend fun verify(check: HostCheck): Boolean = checks.getOrPut(check.machine) { Mutex() }.withLock {
        val current = store.current(check.machine)
        if (current.generation != check.generation) return@withLock current.health == HostHealth.ONLINE
        val targets = endpoints().filter {
            it.machine == check.machine &&
                (!preferences.localNetworkOnly || LocalNetworkPolicy.isPotentiallyLocalHost(it.host)) &&
                !tunnelDown(it.host)
        }
        // A policy restriction or a tunnel that is down (e.g. after the background) is not evidence that a host went down.
        if (targets.isEmpty()) return@withLock false
        val reachable = coroutineScope {
            targets.distinctBy { it.host to it.port }.map { target -> async {
                runInterruptible {
                    try { Socket().use { it.connect(InetSocketAddress(target.host, target.port), if (tunnelled(target.host)) 3_000 else 1500) }; true }
                    catch (_: java.io.IOException) { false }
                }
            } }.awaitAll().any { it }
        }
        if (reachable) store.online(check.machine) else store.offline(check)
        store.current(check.machine).health == HostHealth.ONLINE
    }

    fun mediaSystem(source: String, id: String): String? = when {
        FileKeys.owns(id) -> runCatching { "files:" + FileKeys.parse(id).first }.getOrNull()
        DlnaKeys.owns(id) -> runCatching { "dlna:" + DlnaKeys.parse(id).first }.getOrNull()
        source.lowercase() in setOf("plex", "jellyfin", "emby", "tvheadend") -> source.lowercase()
        else -> null
    }

    /** Network back (e.g. app returning from the background): outages seen without network are rechecked. */
    fun recheckOffline() {
        scope.launch {
            endpoints().distinctBy { it.machine }.filter { store.current(it.machine).health == HostHealth.OFFLINE }
                .forEach { launch { verify(store.begin(it.machine)) } }
        }
    }

    /** One passive confirmation per playback start/resume, shared by all three renderers. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                delay(30_000)
                endpoints().distinctBy { it.machine }.filter { store.current(it.machine).health == HostHealth.OFFLINE }
                    .forEach { verify(store.begin(it.machine)) }
            }
        }
        scope.launch {
            com.ambient.tv.ui.player.BackgroundPlaybackSession.snapshot
                .map { PlaybackHostSignal(it.metadata?.source, it.metadata?.mediaId, it.playing, it.status.error) }
                .distinctUntilChanged().collectLatest { (source, id, playing, error) ->
                    if ((playing || error != null) && source != null && id != null) {
                        val system = mediaSystem(source, id) ?: return@collectLatest
                        val check = begin(system) ?: return@collectLatest
                        verify(check) // Playing cached bytes alone must not mark the host online.
                    }
                }
        }
    }
}
