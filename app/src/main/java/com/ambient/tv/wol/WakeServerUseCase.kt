package com.ambient.tv.wol

import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject

sealed interface WakeState {
    data object SendingPacket : WakeState
    /** [packetsSent] of at most [packets] bursts are out; the next follows after [windowSeconds]. */
    data class WaitingForServer(val elapsedSeconds: Int, val packetsSent: Int = 0, val packets: Int = 0, val windowSeconds: Int = 0) : WakeState
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
        if (reachabilityChecker.isReachable() || hosts.verifySystem("plex")) {
            emit(WakeState.Reachable)
            return@flow
        }

        var sent = 0
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
            reachable = { reachabilityChecker.isReachable() || hosts.verifySystem("plex") },
            onWaiting = { elapsed -> emit(WakeState.WaitingForServer(elapsed, sent, wakeCycles(retries), (timeoutMs / 1000).toInt())) },
            onSent = { attempt, _ -> sent = attempt + 1 },
        )
        emit(if (woke) WakeState.Reachable else WakeState.TimedOut)
    }.catch { error ->
        emit(WakeState.Failed(error.message.orEmpty()))
    }
}
