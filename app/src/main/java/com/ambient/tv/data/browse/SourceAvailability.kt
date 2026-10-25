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

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            hosts.states.map { states -> states.mapValues { it.value.health } }.distinctUntilChanged().collect { synchronized(this@SourceAvailability) { mutable.value++ } }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
            })
        }
    }

    @Synchronized private fun changed() {
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
    @Synchronized fun state(provider: String): SourceHealth =
        if (!hasNetwork()) SourceHealth.OFFLINE else hostState(provider, health[scopes.forProvider(provider)] ?: SourceHealth.UNKNOWN)

    private fun hostState(system: String, service: SourceHealth): SourceHealth =
        if (!hasNetwork()) SourceHealth.OFFLINE else hostAwareSourceHealth(hosts.health(system), service)

    /** [started]: when the request behind it began; a failure older than the last success is stale. */
    @Synchronized private fun report(scope: String, state: SourceHealth, started: Long) {
        if (!order.accept(scope, started, state)) return
        if (health.put(scope, state) != state) mutable.value++
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
                        401, 403 -> SourceHealth.AUTH_REQUIRED
                        in 200..499 -> SourceHealth.ONLINE
                        else -> SourceHealth.UNKNOWN
                    }, started)
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
