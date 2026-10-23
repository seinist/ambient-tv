package com.ambient.tv.wol

import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject

sealed interface WakeState {
    data object SendingPacket : WakeState
    data class WaitingForServer(val elapsedSeconds: Int) : WakeState
    data object Reachable : WakeState
    data object TimedOut : WakeState
    data class Failed(val message: String) : WakeState
}

class WakeServerUseCase @Inject constructor(
    private val wolManager: WolManager,
    private val reachabilityChecker: ServerReachabilityChecker,
    private val preferences: AppPreferences,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
) {
    /** [retries] extra wake cycles after the first; a brief resume probe passes 0. */
    fun wakeAndWait(
        macAddress: String,
        pollIntervalMs: Long = 2500,
        timeoutMs: Long = 60_000,
        retries: Int = preferences.wolRetryCount,
    ): Flow<WakeState> = flow<WakeState> {
        // Checked first so a server that's already up (the common case - every Home
        // revisit calls this, not just cold app start) never shows a wake/waiting spinner
        // or sends a needless magic packet.
        if (hosts.health("plex") == com.ambient.tv.connectivity.HostHealth.ONLINE || reachabilityChecker.isReachable()) {
            emit(WakeState.Reachable)
            return@flow
        }

        val woke = wakeWithRetries(
            retries = retries,
            windowMs = timeoutMs,
            pollMs = pollIntervalMs,
            send = { attempt ->
                emit(WakeState.SendingPacket)
                wolManager.sendWakeBurst(
                    macAddress,
                    wireGuardHost = preferences.host.takeIf { preferences.wireGuardPlexEnabled },
                    retry = attempt > 0,
                ).getOrThrow()
            },
            reachable = { hosts.health("plex") == com.ambient.tv.connectivity.HostHealth.ONLINE || reachabilityChecker.isReachable() },
            onWaiting = { elapsed -> emit(WakeState.WaitingForServer(elapsed)) },
        )
        emit(if (woke) WakeState.Reachable else WakeState.TimedOut)
    }.catch { error ->
        emit(WakeState.Failed(error.message.orEmpty()))
    }
}
