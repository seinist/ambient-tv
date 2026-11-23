package com.ambient.tv.data.browse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

enum class SourceHealth { UNKNOWN, ONLINE, OFFLINE, AUTH_REQUIRED }

/** Network presence is not internet validation: an isolated LAN can still serve media. */
@Singleton
class SourceAvailability @Inject constructor(@ApplicationContext context: Context, private val scopes: BrowseScopes, private val preferences: com.ambient.tv.data.local.AppPreferences, private val hosts: com.ambient.tv.connectivity.HostConnectivity) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(0L)
    val changes = mutable.asStateFlow()
    private val health = mutableMapOf<String, SourceHealth>()
    private val order = SourceReportOrder()
    private var networkGeneration = 0L
    private var accessNetwork: Network? = connectivity.activeNetwork
    private val accessFailures = mutableMapOf<String, Long>()
    /** A reachable sibling port does not confirm this service's last failed data read. */
    private val unconfirmedReads = mutableSetOf<String>()
    /** Read again after a network change, but nothing failed: not shown as offline. */
    private val networkStaleReads = mutableSetOf<String>()

    /** Successful HTTP/port checks never restore an old authenticated playback permission. */
    @Synchronized fun accessGeneration(provider: String, hostSystem: String = provider): String {
        val scope = scopes.forProvider(provider)
        val outages = hosts.machine(hostSystem)?.let { hosts.states.value[it]?.outages } ?: 0L
        return "$networkGeneration:${accessFailures[scope] ?: 0L}:$outages"
    }

    /** Captured live-TV profiles report their own service failure, independent of VOD selection. */
    @Synchronized fun accessFailed(provider: String) {
        val scope = scopes.forProvider(provider)
        accessFailures[scope] = (accessFailures[scope] ?: 0L) + 1L
        unconfirmedReads += scope
        mutable.value++
    }

    /** Display/action state, deliberately stricter than shared physical-host reachability. */
    @Synchronized fun browseUnconfirmed(provider: String): Boolean {
        val scope = scopes.forProvider(provider)
        return browseSourceUnconfirmed(hasNetwork(), hosts.health(provider),
            health.getOrPut(scope) { SourceHealth.UNKNOWN }, scope in unconfirmedReads || scope in networkStaleReads)
    }

    /**
     * What the UI marks as offline: an actual failure of this source or its server. A network
     * or VPN switch only schedules a fresh read ([browseUnconfirmed]); it is not shown.
     */
    @Synchronized fun browseShowsOffline(provider: String): Boolean {
        val scope = scopes.forProvider(provider)
        return browseSourceUnconfirmed(hasNetwork(), hosts.health(provider),
            health.getOrPut(scope) { SourceHealth.UNKNOWN }, scope in unconfirmedReads)
    }

    /**
     * Only a fresh successful data read without cache fallback may clear a saved-view warning.
     * A request that overlaps a newer failure or network change cannot unlock the view.
     * This never grants playback permission; the protected playback verifier owns that proof.
     */
    @Synchronized fun confirmBrowseRead(provider: String, started: String, hostSystem: String = provider): Boolean {
        if (!hasNetwork() || started != accessGeneration(provider, hostSystem)) return false
        report(scopes.forProvider(provider), SourceHealth.ONLINE, order.begin())
        return true
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            hosts.states.map { states -> states.mapValues { it.value.health } }.distinctUntilChanged().collect { synchronized(this@SourceAvailability) { mutable.value++ } }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changed(); hosts.recheckOffline() }
                override fun onLost(network: Network) = changed()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
            })
        }
    }

    @Synchronized private fun changed() {
        val current = connectivity.activeNetwork
        // Internet-validation/capability changes do not disconnect an otherwise usable LAN.
        if (current == accessNetwork) return
        accessNetwork = current
        networkGeneration++
        // Previously loaded live dashboards also need a new read after a network change.
        networkStaleReads += health.keys
        if (!hasNetwork()) listOf("plex", "jellyfin", "emby", "youtube", "spotify").forEach { health[scopes.forProvider(it)] = SourceHealth.OFFLINE }
        else health.replaceAll { _, value -> if (value == SourceHealth.ONLINE) SourceHealth.UNKNOWN else value }
        mutable.value++
    }
    fun online(provider: String) { hosts.online(hosts.begin(provider)); report(scopes.forProvider(provider), SourceHealth.ONLINE, order.begin()) }
    fun unreachable(provider: String) { hosts.failure(hosts.begin(provider)); report(scopes.forProvider(provider), SourceHealth.OFFLINE, order.begin()) }
    fun mediaState(id: String): SourceHealth {
        if (mediaProvider(id) == "files") {
            val source = runCatching { com.ambient.tv.data.files.FileKeys.parse(id).first }.getOrNull()
            if (preferences.fileSources.any { it.id == source && it.kind == "local" }) return SourceHealth.ONLINE
        }
        val system = hosts.mediaSystem(mediaProvider(id), id)
        if (system != null && system.contains(':')) return hostState(system, SourceHealth.UNKNOWN)
        return state(mediaProvider(id))
    }
    fun hasNetwork(): Boolean = connectivity.activeNetwork != null
    /** A sibling's open port must not turn failed service artwork into repeated network loads. */
    @Synchronized fun reportedState(provider: String): SourceHealth =
        if (!hasNetwork()) SourceHealth.OFFLINE else health[scopes.forProvider(provider)] ?: SourceHealth.UNKNOWN

    @Synchronized fun state(provider: String): SourceHealth =
        if (!hasNetwork()) SourceHealth.OFFLINE else hostState(provider, health[scopes.forProvider(provider)] ?: SourceHealth.UNKNOWN)

    private fun hostState(system: String, service: SourceHealth): SourceHealth =
        if (!hasNetwork()) SourceHealth.OFFLINE else hostAwareSourceHealth(hosts.health(system), service)

    /** [started]: when the request behind it began; a failure older than the last success is stale. */
    @Synchronized private fun report(scope: String, state: SourceHealth, started: Long, confirmsRead: Boolean = true) {
        if (!order.accept(scope, started, state)) return
        val invalidatesAccess = state == SourceHealth.OFFLINE || state == SourceHealth.AUTH_REQUIRED || state == SourceHealth.UNKNOWN
        fun pendingRead() = scope in unconfirmedReads || scope in networkStaleReads
        val wasUnconfirmed = pendingRead()
        if (invalidatesAccess) {
            accessFailures[scope] = (accessFailures[scope] ?: 0L) + 1L
            unconfirmedReads += scope
        } else if (state == SourceHealth.ONLINE && confirmsRead) { unconfirmedReads -= scope; networkStaleReads -= scope }
        if (health.put(scope, state) != state || invalidatesAccess || wasUnconfirmed != pendingRead()) mutable.value++
    }

    fun interceptor(provider: String) = Interceptor { chain ->
        val scope = scopes.forProvider(provider)
        val check = hosts.begin(provider)
        val started = order.begin()
        try {
            chain.proceed(chain.request()).also { response ->
                if (scope == scopes.forProvider(provider)) {
                    // Even an authentication/server error proves host reachability.
                    if (response.networkResponse != null) hosts.online(check)
                    report(scope, when (response.code) {
                        // An administrator-only request refused for a regular user says nothing about the login.
                        401, 403 -> if (com.ambient.tv.network.ElevatedRequest.isElevated(chain.request())) SourceHealth.ONLINE else SourceHealth.AUTH_REQUIRED
                        in 200..499 -> SourceHealth.ONLINE
                        else -> SourceHealth.UNKNOWN
                    }, started, confirmsRead = response.networkResponse != null && response.isSuccessful &&
                        browseResponseConfirmsRead(provider, chain.request().url.encodedPath))
                }
            }
        } catch (error: IOException) {
            if (!chain.call().isCanceled() && scope == scopes.forProvider(provider)) {
                hosts.failure(check)
                report(scope, SourceHealth.OFFLINE, started)
            }
            throw error
        }
    }

    fun notifyBlocked() = com.ambient.tv.notifications.AppNotifications.post(com.ambient.tv.notifications.AppNotice(
        key = "playback:unavailable", message = com.ambient.tv.R.string.browse_playback_unavailable,
        phase = com.ambient.tv.notifications.NoticePhase.ERROR, kind = com.ambient.tv.notifications.NoticeKind.PLAYBACK,
    ))
}

fun mediaProvider(id: String): String = when {
    com.ambient.tv.data.jellyfin.JellyfinBackend.owns(id) -> "jellyfin"
    com.ambient.tv.data.emby.EmbyBackend.owns(id) -> "emby"
    com.ambient.tv.data.files.FileKeys.owns(id) -> "files"
    com.ambient.tv.data.dlna.DlnaKeys.owns(id) -> "dlna"
    com.ambient.tv.data.shared.SharedMediaRepository.owns(id) -> "shared"
    else -> "plex"
}

/**
 * Orders the health reports of one source like [com.ambient.tv.connectivity.HostHealthStore]:
 * a success always applies, a failure (offline, server error) only when its request started after
 * the last applied success. A request that hung through a wake and fails late no longer turns a
 * server that answered meanwhile back to "offline".
 */
internal class SourceReportOrder {
    private var clock = 0L
    private val lastSuccess = mutableMapOf<String, Long>()

    @Synchronized fun begin(): Long = ++clock

    @Synchronized fun accept(scope: String, started: Long, state: SourceHealth): Boolean {
        if (state == SourceHealth.ONLINE || state == SourceHealth.AUTH_REQUIRED) {
            lastSuccess[scope] = ++clock
            return true
        }
        return started > (lastSuccess[scope] ?: 0L)
    }
}

/** Host recovery removes an old offline flag, but never grants service access. */
internal fun hostAwareSourceHealth(host: com.ambient.tv.connectivity.HostHealth, service: SourceHealth): SourceHealth = when {
    host == com.ambient.tv.connectivity.HostHealth.OFFLINE -> SourceHealth.OFFLINE
    service == SourceHealth.AUTH_REQUIRED -> service
    host == com.ambient.tv.connectivity.HostHealth.ONLINE && service == SourceHealth.OFFLINE -> SourceHealth.UNKNOWN
    else -> service
}

/** An open NAS port cannot erase a known failure of one of its services. */
internal fun browseSourceUnconfirmed(
    hasNetwork: Boolean,
    host: com.ambient.tv.connectivity.HostHealth,
    service: SourceHealth,
    failedRead: Boolean,
): Boolean = !hasNetwork || host == com.ambient.tv.connectivity.HostHealth.OFFLINE || failedRead ||
    service == SourceHealth.OFFLINE || service == SourceHealth.AUTH_REQUIRED

/** Public identity probes show reachability, not a recovered catalog/account. */
internal fun browseResponseConfirmsRead(provider: String, path: String): Boolean = when (provider) {
    "plex" -> path.trimEnd('/') !in setOf("", "/identity")
    "jellyfin", "emby" -> !path.trimEnd('/').endsWith("/System/Info/Public", ignoreCase = true)
    else -> true
}
