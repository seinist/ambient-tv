package com.ambient.tv.server

import com.ambient.tv.wol.wakeWithRetries
import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.data.emby.EmbyRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.wol.MacAddressResolver
import com.ambient.tv.wol.WolManager
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.connectivity.ResumeConnectivityPolicy
import com.ambient.tv.di.SharedOkHttp
import javax.inject.Provider
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class EmbyConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val repository: EmbyRepository,
    private val wolManager: WolManager,
    private val macResolver: MacAddressResolver,
    private val wireGuardRoute: WireGuardRouteController,
    private val resumeConnectivity: Provider<ResumeConnectivityMonitor>,
    @SharedOkHttp connectionPool: ConnectionPool,
    @SharedOkHttp dispatcher: Dispatcher,
) {
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .addInterceptor(com.ambient.tv.diagnostics.DiagnosticHttpInterceptor).build()
    private val _state = MutableStateFlow<ServerConnectionState>(ServerConnectionState.NotConfigured)
    val state: StateFlow<ServerConnectionState> = combine(_state, hosts.states) { service, _ ->
        com.ambient.tv.connectivity.hostAwareConnectionState(service, hosts.health("emby"))
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, _state.value)
    private var lastSuccessfulCheckMs = 0L
    private var lastServerId: String? = null
    private fun endpointKey() = listOf(preferences.activeEmbyServerId, preferences.embyHost, preferences.embyPort, preferences.embyUseHttps, preferences.embyBasePath).joinToString("|")
    private val connectionMutex = Mutex()


    private val reconnectPause = ReconnectPause()

    fun pauseAutomaticReconnect() { reconnectPause.pause(endpointKey()) }

    suspend fun reconnect(): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            reconnectPause.clear()
            lastSuccessfulCheckMs = 0L
            ensureConnectedLocked()
        }
    }

    /** A single passive health request: no wake, VPN setup, or intermediate UI state. */
    suspend fun checkInBackground(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            if ((!force && !reconnectPause.isPaused(endpointKey())) || !preferences.isEmbyConfigured) return@withLock false
            if (preferences.localNetworkOnly &&
                !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.embyHost)) return@withLock false
            val checkedEndpoint = endpointKey()
            val identity = repository.getIdentity().getOrNull() ?: return@withLock false
            if (checkedEndpoint != endpointKey()) return@withLock false
            _state.value = ServerConnectionState.Online(identity)
            reconnectPause.onlineAgain()
            lastSuccessfulCheckMs = System.currentTimeMillis()
            lastServerId = checkedEndpoint
            true
        }
    }

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock { ensureConnectedLocked() }
    }

    suspend fun recheckOnResume(onWaking: (() -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            lastSuccessfulCheckMs = 0L
            ensureConnectedLocked(
                wakeTimeoutMs = ResumeConnectivityPolicy.BRIEF_WAKE_TIMEOUT_MS,
                wakePollMs = ResumeConnectivityPolicy.BRIEF_WAKE_POLL_MS,
                wakeRetries = 0,
                onWaking = onWaking,
            )
        }
    }

    private suspend fun ensureConnectedLocked(
        wakeTimeoutMs: Long = 60_000L,
        wakePollMs: Long = 2_500L,
        wakeRetries: Int = preferences.wolRetryCount,
        onWaking: (() -> Unit)? = null,
    ): Boolean {
        val check = hosts.begin("emby")
        val endpoint = endpointKey()
        val result = connectService(wakeTimeoutMs, wakePollMs, wakeRetries, onWaking)
        if (endpoint == endpointKey() && !result) hosts.failure(check)
        return result
    }

    private suspend fun connectService(
        wakeTimeoutMs: Long = 60_000L,
        wakePollMs: Long = 2_500L,
        wakeRetries: Int = preferences.wolRetryCount,
        onWaking: (() -> Unit)? = null,
    ): Boolean {
        if (reconnectPause.isPaused(endpointKey())) return _state.value is ServerConnectionState.Online && hosts.health("emby") != com.ambient.tv.connectivity.HostHealth.OFFLINE
        if (!preferences.isEmbyConfigured) { _state.value = ServerConnectionState.NotConfigured; return false }
        val host = NetworkEndpoint.normalizeHost(preferences.embyHost)
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isPotentiallyLocalHost(host)) {
            _state.value = ServerConnectionState.Error(context.getString(R.string.server_non_local_blocked)); return false
        }
        if (!wireGuardRoute.ensureForEmby().fold(onSuccess = { true }, onFailure = { error ->
                _state.value = ServerConnectionState.Error(error.message ?: context.getString(R.string.wg_failed))
                false
            })) return false
        val endpointKey = listOf(
            preferences.activeEmbyServerId.orEmpty(),
            preferences.embyUseHttps,
            host,
            preferences.embyPort,
            preferences.embyBasePath,
        ).joinToString("|")
        if (connectionReusable(_state.value is ServerConnectionState.Online && hosts.health("emby") != com.ambient.tv.connectivity.HostHealth.OFFLINE, lastServerId, endpointKey,
                lastSuccessfulCheckMs, System.currentTimeMillis(), windowMs = 30_000L)) {
            resumeConnectivity.get().noteHealthyNow()
            return true
        }
        _state.value = ServerConnectionState.Checking
        var reachable = isReachable()
        if (!reachable && !hosts.verifySystem("emby")) {
            val mac = preferences.embyMacAddress
            if (!preferences.embyAutoWakeEnabled || mac.isNullOrBlank()) { _state.value = ServerConnectionState.Offline; return false }
            _state.value = ServerConnectionState.SendingWakePacket
            onWaking?.invoke()
            reachable = wakeWithRetries(
                retries = wakeRetries,
                windowMs = wakeTimeoutMs,
                pollMs = wakePollMs,
                send = { attempt ->
                    if (attempt > 0) { _state.value = ServerConnectionState.SendingWakePacket; onWaking?.invoke() }
                    wolManager.sendWakeBurst(
                        mac,
                        wireGuardHost = preferences.embyHost.takeIf { preferences.wireGuardEmbyEnabled },
                        retry = attempt > 0,
                    )
                },
                reachable = { isReachable() || hosts.verifySystem("emby") },
                onWaiting = { elapsed ->
                    _state.value = ServerConnectionState.Waking(elapsed)
                    onWaking?.invoke()
                },
            )
            if (!reachable) { _state.value = ServerConnectionState.WakeTimedOut; return false }
        }
        _state.value = ServerConnectionState.Connecting
        val identity = repository.getIdentity().getOrElse {
            _state.value = ServerConnectionState.Error(com.ambient.tv.notifications.failureReasonText(context, it, "${preferences.embyHost}:${preferences.embyPort}")); return false
        }
        _state.value = ServerConnectionState.Online(identity)
        lastServerId = endpointKey
        lastSuccessfulCheckMs = System.currentTimeMillis()
        if (preferences.embyMacAddress.isNullOrBlank()) {
            macResolver.resolve(host, preferences.embyPort)?.macAddress?.let(preferences::updateActiveEmbyServerMac)
        }
        hosts.online(hosts.begin("emby")) // MAC discovery may have changed the shared machine key.
        resumeConnectivity.get().noteHealthyNow()
        return true
    }

    suspend fun testCurrentServer() = withContext(Dispatchers.IO) {
        val vpn = wireGuardRoute.ensureForEmby()
        if (vpn.isFailure) return@withContext Result.failure(vpn.exceptionOrNull() ?: IllegalStateException(context.getString(R.string.wg_failed)))
        repository.getIdentity()
    }

    private fun baseUrl(): String {
        val scheme = if (preferences.embyUseHttps) "https" else "http"
        return "$scheme://${NetworkEndpoint.authorityHost(preferences.embyHost)}:${preferences.embyPort}${preferences.embyBasePath}/System/Info/Public"
    }

    private suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(baseUrl()).get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
