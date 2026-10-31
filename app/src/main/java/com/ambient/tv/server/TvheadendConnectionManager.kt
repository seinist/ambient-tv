package com.ambient.tv.server

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.tvheadend.data.TvheadendRepository
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.wol.WolManager
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
    private val browseReconnects: com.ambient.tv.data.browse.BrowseReconnects,
    private val repository: TvheadendRepository,
    private val wolManager: WolManager,
    private val wireGuardRoute: WireGuardRouteController,
    private val resumeConnectivity: Provider<ResumeConnectivityMonitor>,
) {
    private val _state = MutableStateFlow<ServerConnectionState>(ServerConnectionState.NotConfigured)
    val state: StateFlow<ServerConnectionState> = combine(_state, hosts.states) { service, _ ->
        val hostHealth = hosts.health("tvheadend")
        // Invalidate the cached identity on a confirmed outage. A later open machine port
        // must not resurrect an old Online state before this service answers its own API.
        if (com.ambient.tv.connectivity.shouldInvalidateConfirmedService(service, hostHealth)) {
            // Do not override a newer Checking/Waking/Online result from a concurrent connect.
            _state.compareAndSet(service, ServerConnectionState.Offline)
        }
        com.ambient.tv.connectivity.hostAwareConnectionState(service, hostHealth)
    }.stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, _state.value)
    @Volatile private var lastSuccessfulCheckMs = 0L
    private var lastServerId: String? = null
    private fun endpointKey() = listOf(preferences.tvheadendHost, preferences.tvheadendPort, preferences.tvheadendUseHttps, preferences.tvheadendUsername).joinToString("|")
    /** Short checks only (background probe); a wake never holds it. */
    private val connectionMutex = Mutex()
    private val connectRun = SharedConnectRun(CoroutineScope(SupervisorJob() + Dispatchers.IO))
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

    /** Stop the app-scoped cold-start wake, not just the caller waiting on it. */
    fun cancelStartupWake() {
        pauseAutomaticReconnect()
        connectRun.cancel()
    }

    /** An explicit user retry re-enables waking without changing saved WOL preferences. */
    fun allowWakeRetry() = resumeAutomaticReconnect()

    /** Joins a connect that is already running instead of starting a second one behind it. */
    suspend fun reconnect(): Boolean = withContext(Dispatchers.IO) {
        resumeAutomaticReconnect()
        if (!connectRun.active) lastSuccessfulCheckMs = 0L
        val endpoint = endpointKey()
        connectRun.joinOrStart(endpoint) { connect(forceNewWake = true) }.also {
            if (it && endpoint == endpointKey()) browseReconnects.request("tvheadend")
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
            // A passive probe must also run for a normally unpaused offline server.
            // A dismissed wake remains dismissed: only a confirmed service response clears it.
            if (!shouldProbeInBackground(
                    configured = configured,
                    force = force,
                    serviceOnline = _state.value is ServerConnectionState.Online,
                    hostOnline = hosts.health("tvheadend") == com.ambient.tv.connectivity.HostHealth.ONLINE,
                )) return@withLock false
            if (preferences.localNetworkOnly &&
                !LocalNetworkPolicy.isPotentiallyLocalHost(preferences.tvheadendHost)) return@withLock false
            val checkedEndpoint = endpointKey()
            val connectGeneration = connectRun.generation
            if (!repository.portReachable()) return@withLock false
            val info = retryTransient(System.currentTimeMillis() + retryForMs) { repository.probeServerInfo() }.getOrNull()
                ?: return@withLock false
            // A connect that started meanwhile owns the state; never overwrite its result.
            if (checkedEndpoint != endpointKey() || connectRun.startedSince(connectGeneration)) return@withLock false
            hosts.online(hosts.begin("tvheadend")) // Successful API probe overrides stale port failures.
            _state.value = ServerConnectionState.Online(identity(info.version))
            resumeAutomaticReconnect()
            lastSuccessfulCheckMs = System.currentTimeMillis()
            lastServerId = checkedEndpoint
            browseReconnects.request("tvheadend")
            true
        }
    }

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        connectRun.joinOrStart(endpointKey()) { connect() }
    }

    /**
     * Forced re-check (resume, playback recovery): joins a running wake, otherwise runs the full
     * wake with its whole budget - a server needing 30 s to boot outlasts any short wait.
     */
    suspend fun recheckOnResume(onWaking: (() -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        // Invalidate even if an ordinary Home entry is currently reusing a cached connection.
        lastSuccessfulCheckMs = 0L
        val endpoint = endpointKey()
        watchWaking(_state, onWaking) {
            val joined = connectRun.joinOrStart(endpoint) { connect() }
            // A concurrent ensureConnected() may have returned its cached Online result
            // before invalidation. Repeat only if no fresh API confirmation was recorded.
            if (endpoint == endpointKey() && needsPostJoinResumeVerification(joined, lastSuccessfulCheckMs)) {
                connectRun.joinOrStart(endpoint) { connect() }
            } else joined
        }.also {
            if (it && endpoint == endpointKey()) browseReconnects.request("tvheadend")
        }
    }

    private suspend fun connect(forceNewWake: Boolean = false): Boolean {
        val before = _state.value
        var freshlyConfirmed = false
        val check = hosts.begin("tvheadend")
        val endpoint = endpointKey()
        return try {
            connectService(forceNewWake = forceNewWake, onConnected = { freshlyConfirmed = true }).also { connected ->
                if (endpoint == endpointKey()) {
                    if (!connected) hosts.failure(check)
                    else if (freshlyConfirmed) browseReconnects.request("tvheadend")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Never leave "waking" behind: nothing would end it, and its card would keep waiting.
            if (isInFlightConnectState(_state.value)) {
                _state.value = settledStateAfterCancel(before, paused = reconnectPause.isPaused(endpoint))
            }
            throw cancelled
        }
    }

    /** TVHeadend's API answers (a 4xx too: it runs, the identity request then reports the login). */
    private suspend fun serviceReady(): Boolean {
        if (!repository.portReachable()) return false
        val probe = repository.probeServerInfo()
        val error = probe.exceptionOrNull() ?: return true
        return (error as? com.ambient.tv.tvheadend.data.TvheadendHttpException)?.code in 400..499
    }

    private suspend fun connectService(
        wakeTimeoutMs: Long = 60_000L,
        wakePollMs: Long = 2_500L,
        wakeRetries: Int = preferences.wolRetryCount,
        onConnected: () -> Unit,
        forceNewWake: Boolean = false,
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
        val mac = preferences.tvheadendMacAddress
        // Success only once TVHeadend's API answers; another open port of the machine is not enough.
        val wait = waitForService(
            serviceReady = ::serviceReady,
            machineAwake = { hosts.verifySystem("tvheadend") },
            hostHealth = { hosts.health("tvheadend") },
            canWake = preferences.tvheadendAutoWakeEnabled && !mac.isNullOrBlank(),
            send = { attempt ->
                wolManager.sendWakeBurst(
                    mac.orEmpty(),
                    wireGuardHost = preferences.tvheadendHost.takeIf { preferences.wireGuardTvheadendEnabled },
                    retry = forceNewWake || attempt > 0,
                )
            },
            retries = wakeRetries,
            windowMs = wakeTimeoutMs,
            pollMs = wakePollMs,
            setState = { _state.value = it },
        )
        wait.failure?.let { _state.value = it; return false }
        _state.value = ServerConnectionState.Connecting
        // Right after a wake the API may still answer 503, refuse, or reject the login once.
        val info = retryTransient(
            wait.identityDeadlineMs,
            transient = { isTransientConnectFailure(it) || (wait.waited && com.ambient.tv.notifications.failureHttpCode(it) == 401) },
        ) { repository.probeServerInfo() }.getOrElse {
            _state.value = ServerConnectionState.Error(com.ambient.tv.notifications.failureReasonText(context, it, "${preferences.tvheadendHost}:${preferences.tvheadendPort}"))
            return false
        }
        lastServerId = endpointKey
        lastSuccessfulCheckMs = System.currentTimeMillis()
        hosts.online(hosts.begin("tvheadend"))
        _state.value = ServerConnectionState.Online(identity(info.version))
        resumeConnectivity.get().noteHealthyNow()
        onConnected()
        return true
    }

    /** The shared Online state carries a server identity; TVHeadend only has a version. */
    private fun identity(version: String?) = PlexServerIdentity(machineIdentifier = null, version = version, claimed = false)
}
