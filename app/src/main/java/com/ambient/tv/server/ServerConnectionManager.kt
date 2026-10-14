package com.ambient.tv.server

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.network.LocalNetworkPolicy
import com.ambient.tv.wol.MacAddressResolver
import com.ambient.tv.wol.ServerReachabilityChecker
import com.ambient.tv.wol.WakeServerUseCase
import com.ambient.tv.wol.WakeState
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.connectivity.ResumeConnectivityPolicy
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
import kotlinx.coroutines.flow.asStateFlow

sealed interface ServerConnectionState {
    data object NotConfigured : ServerConnectionState
    data object Checking : ServerConnectionState
    data object Offline : ServerConnectionState
    data object SendingWakePacket : ServerConnectionState
    data class Waking(val elapsedSeconds: Int) : ServerConnectionState
    data object Connecting : ServerConnectionState
    data class Online(val identity: PlexServerIdentity) : ServerConnectionState
    data object WakeTimedOut : ServerConnectionState
    data class Error(val message: String) : ServerConnectionState
}

@Singleton
class ServerConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val repository: MediaRepository,
    private val reachabilityChecker: ServerReachabilityChecker,
    private val wakeServerUseCase: WakeServerUseCase,
    private val macAddressResolver: MacAddressResolver,
    private val wireGuardRoute: WireGuardRouteController,
    private val resumeConnectivity: Provider<ResumeConnectivityMonitor>,
) {
    private var lastConnectedServerId: String? = null
    private var lastSuccessfulCheckMs: Long = 0L

    private val _state = MutableStateFlow<ServerConnectionState>(ServerConnectionState.NotConfigured)
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()

    private val connectionMutex = Mutex()
    private fun endpointKey() = "${preferences.activeServerId}|${preferences.host}:${preferences.port}"

    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        connectionMutex.withLock { ensureConnectedLocked() }
    }

    /**
     * Forced short health probe used after app resume. Invalidates the reuse cache, and if the
     * server is offline with WoL configured sends one rate-limited magic packet then waits briefly.
     */
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
        if (connectionReusable(_state.value is ServerConnectionState.Online, lastConnectedServerId, activeServerId,
                lastSuccessfulCheckMs, System.currentTimeMillis(), CONNECTION_REUSE_MS)) {
            resumeConnectivity.get().noteHealthyNow()
            return true
        }

        _state.value = ServerConnectionState.Checking
        if (!reachabilityChecker.isReachable()) {
            val mac = preferences.macAddress
            if (preferences.autoWakeEnabled && !mac.isNullOrBlank()) {
                var reachable = false
                onWaking?.invoke()
                wakeServerUseCase.wakeAndWait(
                    macAddress = mac,
                    pollIntervalMs = wakePollMs,
                    timeoutMs = wakeTimeoutMs,
                    retries = wakeRetries,
                ).collect { wakeState ->
                    when (wakeState) {
                        WakeState.SendingPacket -> {
                            _state.value = ServerConnectionState.SendingWakePacket
                            onWaking?.invoke()
                        }
                        is WakeState.WaitingForServer -> {
                            _state.value = ServerConnectionState.Waking(wakeState.elapsedSeconds)
                            onWaking?.invoke()
                        }
                        WakeState.Reachable -> reachable = true
                        WakeState.TimedOut -> _state.value = ServerConnectionState.WakeTimedOut
                    }
                }
                if (!reachable) return false
            } else {
                _state.value = ServerConnectionState.Offline
                return false
            }
        }

        _state.value = ServerConnectionState.Connecting
        val identityResult = repository.getIdentity()
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
        resumeConnectivity.get().noteHealthyNow()
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
