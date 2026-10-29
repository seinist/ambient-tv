package com.ambient.tv.server

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.wol.MacAddressResolver
import com.ambient.tv.wol.ServerReachabilityChecker
import com.ambient.tv.wol.WolManager
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import javax.inject.Provider
import com.ambient.tv.vpn.WireGuardRouteController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

sealed interface ServerConnectionState {
    data object NotConfigured : ServerConnectionState
    data object Checking : ServerConnectionState
    data object Offline : ServerConnectionState
    data object SendingWakePacket : ServerConnectionState
    /** [totalSeconds]: the wake's budget including retries (0 = unknown), for a progress display. */
    data class Waking(
        val elapsedSeconds: Int,
        val totalSeconds: Int = 0,
        /** Magic-packet bursts sent so far / at most, and the wait after each (0 = not reported). */
        val packetsSent: Int = 0,
        val packets: Int = 0,
        val windowSeconds: Int = 0,
        /** The machine answers again; only this service is still starting (no more packets go out). */
        val serviceStarting: Boolean = false,
    ) : ServerConnectionState
    data object Connecting : ServerConnectionState
    data class Online(val identity: PlexServerIdentity) : ServerConnectionState
    data object WakeTimedOut : ServerConnectionState
    data class Error(val message: String) : ServerConnectionState
}

@Singleton
class ServerConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val browseReconnects: com.ambient.tv.data.browse.BrowseReconnects,
    private val repository: MediaRepository,
    private val reachabilityChecker: ServerReachabilityChecker,
    private val wolManager: WolManager,
    private val macAddressResolver: MacAddressResolver,
    private val wireGuardRoute: WireGuardRouteController,
    private val resumeConnectivity: Provider<ResumeConnectivityMonitor>,
) {
    private var lastConnectedServerId: String? = null
    private var lastSuccessfulCheckMs: Long = 0L

    private val _state = MutableStateFlow<ServerConnectionState>(ServerConnectionState.NotConfigured)
    val state: StateFlow<ServerConnectionState> = combine(_state, hosts.states) { service, _ ->
        com.ambient.tv.connectivity.hostAwareConnectionState(service, hosts.health("plex"))
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, _state.value)

    /** Short checks only (background probe, server test); a wake never holds it. */
    private val connectionMutex = Mutex()
    private val connectRun = SharedConnectRun(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private fun endpointKey() = "${preferences.activeServerId}|${preferences.host}:${preferences.port}"


    private val reconnectPause = ReconnectPause()

    fun pauseAutomaticReconnect() { reconnectPause.pause(endpointKey()) }

    /** Joins a connect that is already running instead of starting a second one behind it. */
    suspend fun reconnect(): Boolean = withContext(Dispatchers.IO) {
        reconnectPause.clear()
        if (!connectRun.active) lastSuccessfulCheckMs = 0L
        val endpoint = endpointKey()
        connectRun.joinOrStart(endpoint) { connect() }.also {
            if (it && endpoint == endpointKey()) browseReconnects.request("plex")
        }
    }

    /**
     * A passive health request: no wake, VPN setup, or intermediate UI state. While a connect
     * runs it reports the result itself, so this returns at once instead of queueing behind it.
     * [retryForMs] keeps retrying a service that is still starting (5xx, refused) that long.
     */
    suspend fun checkInBackground(force: Boolean = false, retryForMs: Long = 0L): Boolean = withContext(Dispatchers.IO) {
        if (connectRun.active) return@withContext false
        connectionMutex.withLock {
            if ((!force && !reconnectPause.isPaused(endpointKey())) || !preferences.hasPlexEndpoint) return@withLock false
            if (preferences.localNetworkOnly &&
                !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.host)) return@withLock false
            val checkedEndpoint = endpointKey()
            val connectGeneration = connectRun.generation
            val identity = retryTransient(System.currentTimeMillis() + retryForMs) { repository.getIdentity() }.getOrNull()
                ?: return@withLock false
            // A connect that started meanwhile owns the state; never overwrite its result.
            if (checkedEndpoint != endpointKey() || connectRun.startedSince(connectGeneration)) return@withLock false
            _state.value = ServerConnectionState.Online(identity)
            reconnectPause.onlineAgain()
            lastSuccessfulCheckMs = System.currentTimeMillis()
            lastConnectedServerId = checkedEndpoint
            browseReconnects.request("plex")
            true
        }
    }

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        connectRun.joinOrStart(endpointKey()) { connect() }
    }

    /**
     * Forced re-check (resume, playback recovery): invalidates the reuse cache and joins a
     * running wake, otherwise runs the full wake with its whole budget - a server needing 30 s
     * to boot outlasts any short wait.
     */
    suspend fun recheckOnResume(onWaking: (() -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (!connectRun.active) lastSuccessfulCheckMs = 0L
        val endpoint = endpointKey()
        watchWaking(_state, onWaking) { connectRun.joinOrStart(endpoint) { connect() } }.also {
            if (it && endpoint == endpointKey()) browseReconnects.request("plex")
        }
    }

    private suspend fun connect(): Boolean {
        val before = _state.value
        var freshlyConfirmed = false
        val check = hosts.begin("plex")
        val endpoint = endpointKey()
        return try {
            connectService(onConnected = { freshlyConfirmed = true }).also { connected ->
                if (endpoint == endpointKey()) {
                    if (!connected) hosts.failure(check)
                    else if (freshlyConfirmed) browseReconnects.request("plex")
                }
            }
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
        onConnected: () -> Unit,
    ): Boolean {
        if (reconnectPause.isPaused(endpointKey())) return _state.value is ServerConnectionState.Online && hosts.health("plex") != com.ambient.tv.connectivity.HostHealth.OFFLINE
        if (!preferences.hasPlexEndpoint) {
            _state.value = ServerConnectionState.NotConfigured
            return false
        }
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.host)) {
            _state.value = ServerConnectionState.Error(context.getString(R.string.server_non_local_blocked))
            return false
        }
        if (!wireGuardRoute.ensureForPlex().fold(onSuccess = { true }, onFailure = { error ->
                _state.value = ServerConnectionState.Error(error.message ?: context.getString(R.string.wg_failed))
                false
            })) return false

        val activeServerId = endpointKey()
        if (connectionReusable(_state.value is ServerConnectionState.Online && hosts.health("plex") != com.ambient.tv.connectivity.HostHealth.OFFLINE, lastConnectedServerId, activeServerId,
                lastSuccessfulCheckMs, System.currentTimeMillis(), CONNECTION_REUSE_MS)) {
            resumeConnectivity.get().noteHealthyNow()
            return true
        }

        _state.value = ServerConnectionState.Checking
        val mac = preferences.macAddress
        // Success only once Plex itself answers /identity; another open port of the machine is not enough.
        val wait = try {
            waitForService(
                serviceReady = { reachabilityChecker.isReachable() },
                machineAwake = { hosts.verifySystem("plex") },
                hostHealth = { hosts.health("plex") },
                canWake = preferences.autoWakeEnabled && !mac.isNullOrBlank(),
                send = { attempt ->
                    wolManager.sendWakeBurst(
                        mac.orEmpty(),
                        wireGuardHost = preferences.host.takeIf { preferences.wireGuardPlexEnabled },
                        retry = attempt > 0,
                    ).getOrThrow()
                },
                retries = wakeRetries,
                windowMs = wakeTimeoutMs,
                pollMs = wakePollMs,
                setState = { _state.value = it },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = ServerConnectionState.Error(error.message.orEmpty())
            return false
        }
        wait.failure?.let { _state.value = it; return false }

        _state.value = ServerConnectionState.Connecting
        // A server that just started may still answer 503 or refuse once: retry within the wake budget.
        val identityResult = retryTransient(wait.identityDeadlineMs) { repository.getIdentity() }
        val identity = identityResult.getOrNull()
        if (identity == null) {
            _state.value = ServerConnectionState.Error(identityResult.exceptionOrNull()
                ?.let { com.ambient.tv.notifications.failureReasonText(context, it, "${preferences.host}:${preferences.port}") }
                ?: context.getString(R.string.server_connection_failed))
            return false
        }

        if (activeServerId != endpointKey()) return false
        _state.value = ServerConnectionState.Online(identity)
        lastConnectedServerId = activeServerId
        lastSuccessfulCheckMs = System.currentTimeMillis()
        learnMacIfMissing()
        hosts.online(hosts.begin("plex")) // MAC discovery may have changed the shared machine key.
        resumeConnectivity.get().noteHealthyNow()
        onConnected()
        return true
    }

    suspend fun testCurrentServer(): Result<PlexServerIdentity> = withContext(Dispatchers.IO) {
        connectionMutex.withLock { testCurrentServerLocked() }
    }

    private suspend fun testCurrentServerLocked(): Result<PlexServerIdentity> {
        if (!preferences.hasPlexEndpoint) return Result.failure(IllegalStateException(context.getString(R.string.server_not_configured)))
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.host)) {
            val error = IllegalStateException(context.getString(R.string.server_non_local_blocked))
            _state.value = ServerConnectionState.Error(error.message.orEmpty())
            return Result.failure(error)
        }
        val vpn = wireGuardRoute.ensureForPlex()
        if (vpn.isFailure) {
            val error = IllegalStateException(vpn.exceptionOrNull()?.message ?: context.getString(R.string.wg_failed))
            _state.value = ServerConnectionState.Error(error.message.orEmpty())
            return Result.failure(error)
        }
        _state.value = ServerConnectionState.Checking
        // /identity answers without a token; the library list proves the login (and marks the
        // server verified through PlexLoginStateInterceptor).
        val result = repository.getIdentity().mapCatching { identity ->
            repository.getLibrarySections().getOrThrow()
            identity
        }
        result.onSuccess {
            preferences.plexLoginVerified = true
            _state.value = ServerConnectionState.Online(it)
            lastConnectedServerId = endpointKey()
            lastSuccessfulCheckMs = System.currentTimeMillis()
            learnMacIfMissing()
            hosts.online(hosts.begin("plex"))
            browseReconnects.request("plex")
        }.onFailure {
            if (com.ambient.tv.notifications.failureHttpCode(it) == 401) preferences.plexLoginVerified = false
            _state.value = ServerConnectionState.Error(com.ambient.tv.notifications.failureReasonText(context, it, "${preferences.host}:${preferences.port}"))
        }
        return result
    }

    private suspend fun learnMacIfMissing() {
        if (!preferences.macAddress.isNullOrBlank()) return
        val resolution = macAddressResolver.resolve(preferences.host, preferences.port) ?: return
        if (preferences.macAddress.isNullOrBlank()) {
            preferences.updateActiveServerMac(resolution.macAddress)
        }
    }

    private companion object {
        const val CONNECTION_REUSE_MS = 30_000L
    }
}
