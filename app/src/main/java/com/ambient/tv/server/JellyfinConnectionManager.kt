package com.ambient.tv.server

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.data.jellyfin.JellyfinRepository
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.wol.MacAddressResolver
import com.ambient.tv.wol.WolManager
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
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
class JellyfinConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val repository: JellyfinRepository,
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
        com.ambient.tv.connectivity.hostAwareConnectionState(service, hosts.health("jellyfin"))
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, _state.value)
    private var lastSuccessfulCheckMs = 0L
    private var lastServerId: String? = null
    private fun endpointKey() = listOf(preferences.activeJellyfinServerId, preferences.jellyfinHost, preferences.jellyfinPort, preferences.jellyfinUseHttps, preferences.jellyfinBasePath).joinToString("|")
    /** Short checks only (background probe); a wake never holds it. */
    private val connectionMutex = Mutex()
    private val connectRun = SharedConnectRun(CoroutineScope(SupervisorJob() + Dispatchers.IO))


    private val reconnectPause = ReconnectPause()

    fun pauseAutomaticReconnect() { reconnectPause.pause(endpointKey()) }

    /** Joins a connect that is already running instead of starting a second one behind it. */
    suspend fun reconnect(): Boolean = withContext(Dispatchers.IO) {
        reconnectPause.clear()
        if (!connectRun.active) lastSuccessfulCheckMs = 0L
        connectRun.joinOrStart { connect() }
    }

    /**
     * A passive health request: no wake, VPN setup, or intermediate UI state. While a connect
     * runs it reports the result itself, so this returns at once instead of queueing behind it.
     * [retryForMs] keeps retrying a service that is still starting (5xx, refused) that long.
     */
    suspend fun checkInBackground(force: Boolean = false, retryForMs: Long = 0L): Boolean = withContext(Dispatchers.IO) {
        if (connectRun.active) return@withContext false
        connectionMutex.withLock {
            if ((!force && !reconnectPause.isPaused(endpointKey())) || !preferences.isJellyfinConfigured) return@withLock false
            if (preferences.localNetworkOnly &&
                !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.jellyfinHost)) return@withLock false
            val checkedEndpoint = endpointKey()
            val identity = retryTransient(System.currentTimeMillis() + retryForMs) { repository.getIdentity() }.getOrNull()
                ?: return@withLock false
            if (checkedEndpoint != endpointKey()) return@withLock false
            _state.value = ServerConnectionState.Online(identity)
            reconnectPause.onlineAgain()
            lastSuccessfulCheckMs = System.currentTimeMillis()
            lastServerId = checkedEndpoint
            true
        }
    }

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        connectRun.joinOrStart { connect() }
    }

    /**
     * Forced re-check (resume, playback recovery): joins a running wake, otherwise runs the full
     * wake with its whole budget - a server needing 30 s to boot outlasts any short wait.
     */
    suspend fun recheckOnResume(onWaking: (() -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (!connectRun.active) lastSuccessfulCheckMs = 0L
        watchWaking(_state, onWaking) { connectRun.joinOrStart { connect() } }
    }

    private suspend fun connect(): Boolean {
        val before = _state.value
        val check = hosts.begin("jellyfin")
        val endpoint = endpointKey()
        return try {
            connectService().also { connected -> if (endpoint == endpointKey() && !connected) hosts.failure(check) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Never leave "waking" behind: nothing would end it, and its card would keep waiting.
            if (isInFlightConnectState(_state.value)) _state.value = settledStateAfterCancel(before)
            throw cancelled
        }
    }

    private suspend fun connectService(
        wakeTimeoutMs: Long = 60_000L,
        wakePollMs: Long = 2_500L,
        wakeRetries: Int = preferences.wolRetryCount,
    ): Boolean {
        if (reconnectPause.isPaused(endpointKey())) return _state.value is ServerConnectionState.Online && hosts.health("jellyfin") != com.ambient.tv.connectivity.HostHealth.OFFLINE
        if (!preferences.isJellyfinConfigured) { _state.value = ServerConnectionState.NotConfigured; return false }
        val host = NetworkEndpoint.normalizeHost(preferences.jellyfinHost)
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isPotentiallyLocalHost(host)) {
            _state.value = ServerConnectionState.Error(context.getString(R.string.server_non_local_blocked)); return false
        }
        if (!wireGuardRoute.ensureForJellyfin().fold(onSuccess = { true }, onFailure = { error ->
                _state.value = ServerConnectionState.Error(error.message ?: context.getString(R.string.wg_failed))
                false
            })) return false
        val endpointKey = listOf(
            preferences.activeJellyfinServerId.orEmpty(),
            preferences.jellyfinUseHttps,
            host,
            preferences.jellyfinPort,
            preferences.jellyfinBasePath,
        ).joinToString("|")
        if (connectionReusable(_state.value is ServerConnectionState.Online && hosts.health("jellyfin") != com.ambient.tv.connectivity.HostHealth.OFFLINE, lastServerId, endpointKey,
                lastSuccessfulCheckMs, System.currentTimeMillis(), windowMs = 30_000L)) {
            resumeConnectivity.get().noteHealthyNow()
            return true
        }
        _state.value = ServerConnectionState.Checking
        val mac = preferences.jellyfinMacAddress
        // Success only once Jellyfin itself answers; another open port of the machine is not enough.
        val wait = waitForService(
            serviceReady = ::isReachable,
            machineAwake = { hosts.verifySystem("jellyfin") },
            hostHealth = { hosts.health("jellyfin") },
            canWake = preferences.jellyfinAutoWakeEnabled && !mac.isNullOrBlank(),
            send = { attempt ->
                wolManager.sendWakeBurst(
                    mac.orEmpty(),
                    wireGuardHost = preferences.jellyfinHost.takeIf { preferences.wireGuardJellyfinEnabled },
                    retry = attempt > 0,
                )
            },
            retries = wakeRetries,
            windowMs = wakeTimeoutMs,
            pollMs = wakePollMs,
            setState = { _state.value = it },
        )
        wait.failure?.let { _state.value = it; return false }
        _state.value = ServerConnectionState.Connecting
        // A server that just started may still answer 503 or refuse once: retry within the wake budget.
        val identity = retryTransient(wait.identityDeadlineMs) { repository.getIdentity() }.getOrElse {
            _state.value = ServerConnectionState.Error(com.ambient.tv.notifications.failureReasonText(context, it, "${preferences.jellyfinHost}:${preferences.jellyfinPort}")); return false
        }
        _state.value = ServerConnectionState.Online(identity)
        lastServerId = endpointKey
        lastSuccessfulCheckMs = System.currentTimeMillis()
        if (preferences.jellyfinMacAddress.isNullOrBlank()) {
            macResolver.resolve(host, preferences.jellyfinPort)?.macAddress?.let(preferences::updateActiveJellyfinServerMac)
        }
        hosts.online(hosts.begin("jellyfin")) // MAC discovery may have changed the shared machine key.
        resumeConnectivity.get().noteHealthyNow()
        return true
    }

    suspend fun testCurrentServer() = withContext(Dispatchers.IO) {
        val vpn = wireGuardRoute.ensureForJellyfin()
        if (vpn.isFailure) return@withContext Result.failure(vpn.exceptionOrNull() ?: IllegalStateException(context.getString(R.string.wg_failed)))
        repository.getIdentity()
    }

    private fun baseUrl(): String {
        val scheme = if (preferences.jellyfinUseHttps) "https" else "http"
        return "$scheme://${NetworkEndpoint.authorityHost(preferences.jellyfinHost)}:${preferences.jellyfinPort}${preferences.jellyfinBasePath}/System/Info/Public"
    }

    private suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(baseUrl()).get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
