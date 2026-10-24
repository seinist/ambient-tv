package com.ambient.tv.server

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.connectivity.ResumeConnectivityPolicy
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.wol.WolManager
import com.ambient.tv.wol.wakeWithRetries
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TVHeadend's connection, run exactly like Plex, Jellyfin and Emby: one [ServerConnectionState]
 * (checking, waking with its packets, connecting, online, offline, wake timed out, error) that
 * drives the connection notices, the unreachable dialog, hiding it from the menu, "Retry" and
 * the pause after "Continue". [TvheadendRepository] keeps doing the API calls and their caches.
 */
@Singleton
class TvheadendConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
    private val repository: TvheadendRepository,
    private val wolManager: WolManager,
    private val wireGuardRoute: WireGuardRouteController,
    private val resumeConnectivity: Provider<ResumeConnectivityMonitor>,
) {
    private val _state = MutableStateFlow<ServerConnectionState>(ServerConnectionState.NotConfigured)
    val state: StateFlow<ServerConnectionState> = combine(_state, hosts.states) { service, _ ->
        com.ambient.tv.connectivity.hostAwareConnectionState(service, hosts.health("tvheadend"))
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, _state.value)
    private var lastSuccessfulCheckMs = 0L
    private var lastServerId: String? = null
    private fun endpointKey() = listOf(preferences.tvheadendHost, preferences.tvheadendPort, preferences.tvheadendUseHttps, preferences.tvheadendUsername).joinToString("|")
    private val connectionMutex = Mutex()
    private val reconnectPause = ReconnectPause()

    private val configured get() = preferences.isTvheadendConfigured && !preferences.tvheadendLoginRejected

    /** "Continue" in the unreachable dialog: no automatic reconnects or wakes until it answers again. */
    fun pauseAutomaticReconnect() {
        reconnectPause.pause(endpointKey())
        repository.automaticWakePaused = true
    }

    private fun resumeAutomaticReconnect() {
        reconnectPause.clear()
        repository.automaticWakePaused = false
    }

    suspend fun reconnect(): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            resumeAutomaticReconnect()
            lastSuccessfulCheckMs = 0L
            ensureConnectedLocked()
        }
    }

    /** A single passive health request: no wake, VPN setup, or intermediate UI state. */
    suspend fun checkInBackground(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            if ((!force && !reconnectPause.isPaused(endpointKey())) || !configured) return@withLock false
            if (preferences.localNetworkOnly &&
                !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.tvheadendHost)) return@withLock false
            val checkedEndpoint = endpointKey()
            if (!repository.portReachable()) return@withLock false
            val info = repository.probeServerInfo().getOrNull() ?: return@withLock false
            if (checkedEndpoint != endpointKey()) return@withLock false
            _state.value = ServerConnectionState.Online(identity(info.version))
            resumeAutomaticReconnect()
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
        val check = hosts.begin("tvheadend")
        val endpoint = endpointKey()
        val result = connectService(wakeTimeoutMs, wakePollMs, wakeRetries, onWaking)
        if (endpoint == endpointKey() && !result) hosts.failure(check)
        return result
    }

    private suspend fun connectService(
        wakeTimeoutMs: Long,
        wakePollMs: Long,
        wakeRetries: Int,
        onWaking: (() -> Unit)?,
    ): Boolean {
        if (reconnectPause.isPaused(endpointKey())) return _state.value is ServerConnectionState.Online && hosts.health("tvheadend") != HostHealth.OFFLINE
        if (!configured) { _state.value = ServerConnectionState.NotConfigured; return false }
        val host = NetworkEndpoint.normalizeHost(preferences.tvheadendHost)
        if (preferences.localNetworkOnly && !LocalNetworkPolicy.isPotentiallyLocalHost(host)) {
            _state.value = ServerConnectionState.Error(context.getString(R.string.server_non_local_blocked)); return false
        }
        if (!wireGuardRoute.ensureForTvheadend().fold(onSuccess = { true }, onFailure = { error ->
                _state.value = ServerConnectionState.Error(error.message ?: context.getString(R.string.wg_failed))
                false
            })) return false
        val endpointKey = endpointKey()
        if (connectionReusable(_state.value is ServerConnectionState.Online && hosts.health("tvheadend") != HostHealth.OFFLINE, lastServerId, endpointKey,
                lastSuccessfulCheckMs, System.currentTimeMillis(), windowMs = 30_000L)) {
            resumeConnectivity.get().noteHealthyNow()
            return true
        }
        _state.value = ServerConnectionState.Checking
        var reachable = repository.portReachable()
        if (!reachable && !hosts.verifySystem("tvheadend")) {
            val mac = preferences.tvheadendMacAddress
            if (!preferences.tvheadendAutoWakeEnabled || mac.isNullOrBlank()) { _state.value = ServerConnectionState.Offline; return false }
            _state.value = ServerConnectionState.SendingWakePacket
            onWaking?.invoke()
            var sentPackets = 0
            reachable = wakeWithRetries(
                retries = wakeRetries,
                windowMs = wakeTimeoutMs,
                pollMs = wakePollMs,
                send = { attempt ->
                    if (attempt > 0) { _state.value = ServerConnectionState.SendingWakePacket; onWaking?.invoke() }
                    wolManager.sendWakeBurst(
                        mac,
                        wireGuardHost = preferences.tvheadendHost.takeIf { preferences.wireGuardTvheadendEnabled },
                        retry = attempt > 0,
                    )
                },
                reachable = { repository.portReachable() || hosts.verifySystem("tvheadend") },
                onWaiting = { elapsed ->
                    _state.value = ServerConnectionState.Waking(elapsed,
                        (com.ambient.tv.wol.wakeBudgetMs(wakeRetries, wakeTimeoutMs) / 1000).toInt(),
                        sentPackets, com.ambient.tv.wol.wakeCycles(wakeRetries), (wakeTimeoutMs / 1000).toInt())
                    onWaking?.invoke()
                },
                onSent = { attempt, _ -> sentPackets = attempt + 1 },
            )
            if (!reachable) { _state.value = ServerConnectionState.WakeTimedOut; return false }
        }
        _state.value = ServerConnectionState.Connecting
        val info = repository.probeServerInfo().getOrElse {
            _state.value = ServerConnectionState.Error(com.ambient.tv.notifications.failureReasonText(context, it, "${preferences.tvheadendHost}:${preferences.tvheadendPort}"))
            return false
        }
        _state.value = ServerConnectionState.Online(identity(info.version))
        lastServerId = endpointKey
        lastSuccessfulCheckMs = System.currentTimeMillis()
        hosts.online(hosts.begin("tvheadend"))
        resumeConnectivity.get().noteHealthyNow()
        return true
    }

    /** The shared Online state carries a server identity; TVHeadend only has a version. */
    private fun identity(version: String?) = PlexServerIdentity(machineIdentifier = null, version = version, claimed = false)
}
