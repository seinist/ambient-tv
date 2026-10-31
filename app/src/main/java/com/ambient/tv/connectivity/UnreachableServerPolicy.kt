package com.ambient.tv.connectivity

import com.ambient.tv.server.ServerConnectionState

/** How a configured media server looks after the latest connection attempt. */
enum class ServerProbe {
    /** Not turned on; saved settings are irrelevant here. */
    INACTIVE,
    /** Still checking or waking. Not a failure yet. */
    PENDING,
    REACHABLE,
    /** The machine answers, but this service has not yet confirmed usable access. */
    HOST_REACHABLE,
    UNREACHABLE,
}

data class UnreachableServerInput(
    val probes: Map<String, ServerProbe>,
    /** Enabled systems that do not use the failed media-server connection (YouTube, files, …). */
    val activeIndependentSystems: Set<String> = emptySet(),
)

data class UnreachableServerDecision(
    val unreachable: Set<String> = emptySet(),
    val pending: Boolean = false,
    /** True when something else is active and reachable, so the app stays usable around the offline server. */
    val canDismiss: Boolean = false,
)

fun probeOf(active: Boolean, state: ServerConnectionState): ServerProbe {
    if (!active) return ServerProbe.INACTIVE
    return when (state) {
        is ServerConnectionState.Online -> ServerProbe.REACHABLE
        ServerConnectionState.Offline,
        ServerConnectionState.WakeTimedOut,
        is ServerConnectionState.Error,
        -> ServerProbe.UNREACHABLE
        // Not started yet is not a failure and must not hold the dialog open.
        ServerConnectionState.NotConfigured -> ServerProbe.INACTIVE
        ServerConnectionState.Checking,
        ServerConnectionState.Connecting,
        ServerConnectionState.SendingWakePacket,
        is ServerConnectionState.Waking,
        -> ServerProbe.PENDING
    }
}

fun decideUnreachableServers(input: UnreachableServerInput): UnreachableServerDecision {
    val unreachable = input.probes.filterValues { it == ServerProbe.UNREACHABLE || it == ServerProbe.HOST_REACHABLE }.keys
    val pending = input.probes.any { it.value == ServerProbe.PENDING }
    val otherMediaReachable = input.probes.any { (id, probe) ->
        probe == ServerProbe.REACHABLE && id !in unreachable
    }
    val othersUsable = !pending && unreachable.isNotEmpty() &&
        (otherMediaReachable || input.activeIndependentSystems.isNotEmpty())
    return UnreachableServerDecision(
        unreachable = unreachable,
        pending = pending,
        canDismiss = othersUsable,
    )
}

/**
 * A foreground recheck owns connection presentation until its result hand-off has settled.
 * [ResumeConnectivityStatus.Progress] intentionally includes the short completed-result window:
 * Home uses that window to replace a brief WakeTimedOut with its full wake attempt.
 */
fun resumeHoldsUnreachablePrompt(status: ResumeConnectivityStatus): Boolean =
    status == ResumeConnectivityStatus.Checking || status is ResumeConnectivityStatus.Progress

/**
 * Servers shown as offline after this probe round. A server becomes offline once a settled
 * round finds it unreachable, and stays so through rechecks and wakes (PENDING) until it
 * answers or is turned off; otherwise each recheck would flash its half-loaded library.
 */
fun nextOfflineSystems(previous: Set<String>, probes: Map<String, ServerProbe>, settled: Boolean): Set<String> {
    // A NAS/PC answering on another port is not enough: the media service still needs to
    // confirm its own API identity before the offline screen gives way to library content.
    val kept = previous.filterTo(mutableSetOf()) {
        probes[it] == ServerProbe.PENDING || probes[it] == ServerProbe.UNREACHABLE ||
            probes[it] == ServerProbe.HOST_REACHABLE
    }
    if (settled) kept += probes.filterValues {
        it == ServerProbe.UNREACHABLE || it == ServerProbe.HOST_REACHABLE
    }.keys
    return kept
}

/** Media server whose library [route] shows, or null for any other page. */
fun libraryRouteSystem(route: String?): String? {
    if (route.isNullOrBlank()) return null
    val path = route.substringBefore('?')
    if (path == "plex" || path == "jellyfin" || path == "emby") return path
    if (!path.startsWith("section/")) return null
    val key = path.removePrefix("section/")
    return when {
        key.startsWith("j:") -> "jellyfin"
        key.startsWith("e:") -> "emby"
        else -> "plex"
    }
}

/** Host recovery clears stale reconnect prompts without claiming service authentication. */
fun hostAwareProbe(configured: Boolean, service: ServerConnectionState, host: HostHealth): ServerProbe =
    if (!configured) ServerProbe.INACTIVE
    else if (probeOf(true, service) == ServerProbe.PENDING) ServerProbe.PENDING
    else when (host) {
        HostHealth.ONLINE -> if (service is ServerConnectionState.Online) ServerProbe.REACHABLE else ServerProbe.HOST_REACHABLE
        HostHealth.OFFLINE -> ServerProbe.UNREACHABLE
        HostHealth.UNKNOWN -> probeOf(true, service)
    }

/** Host outages invalidate a usable connection, never an active attempt or its error detail. */
internal fun hostAwareConnectionState(service: ServerConnectionState, host: HostHealth): ServerConnectionState =
    if (host == HostHealth.OFFLINE && service is ServerConnectionState.Online) ServerConnectionState.Offline
    else service
