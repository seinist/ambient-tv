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
    /** True only when something else is already active and reachable, so Continue may close the dialog. */
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
    val unreachable = input.probes.filterValues { it == ServerProbe.UNREACHABLE }.keys
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

/** Continue records a hide only when another system can actually take over. */
fun acknowledgedAfterConfirm(acknowledged: Set<String>, decision: UnreachableServerDecision): Set<String> =
    if (decision.canDismiss) acknowledged + decision.unreachable else acknowledged

/** A system stays hidden only while it is still unreachable. Coming back shows it again. Nothing is deleted. */
fun hiddenUnreachableSystems(unreachable: Set<String>, acknowledged: Set<String>): Set<String> =
    unreachable.intersect(acknowledged)

/** Library routes that belong to a hidden system. Playback and settings are left alone. */
fun libraryRouteOwnedByHiddenSystem(route: String?, hidden: Set<String>): Boolean {
    if (route.isNullOrBlank() || hidden.isEmpty()) return false
    val path = route.substringBefore('?')
    if (path == "plex") return "plex" in hidden
    if (path == "jellyfin") return "jellyfin" in hidden
    if (path == "emby") return "emby" in hidden
    if (!path.startsWith("section/")) return false
    val key = path.removePrefix("section/")
    return when {
        key.startsWith("j:") -> "jellyfin" in hidden
        key.startsWith("e:") -> "emby" in hidden
        else -> "plex" in hidden
    }
}

/**
 * The confirm dialog is the dismissible path. It stays off setup, settings, and playback,
 * and it is not shown when nothing else works (that case keeps the blocking empty state).
 */
fun shouldPresentUnreachableDialog(route: String?, prompt: Boolean, canDismiss: Boolean): Boolean {
    if (!prompt || !canDismiss) return false
    val path = route?.substringBefore('?').orEmpty()
    if (path.isEmpty()) return false
    if (path == "settings" || path == "server_select" || path.startsWith("initial_setup") || path.startsWith("setup_")) return false
    if (path.startsWith("player/") || path.startsWith("youtube_player") || path.startsWith("tvh_player") || path.startsWith("spotify_player")) return false
    return true
}

/** Host recovery clears stale reconnect prompts without claiming service authentication. */
fun hostAwareProbe(configured: Boolean, service: ServerConnectionState, host: HostHealth): ServerProbe =
    if (!configured) ServerProbe.INACTIVE else when (host) {
        HostHealth.ONLINE -> if (service is ServerConnectionState.Online) ServerProbe.REACHABLE else ServerProbe.HOST_REACHABLE
        HostHealth.OFFLINE -> ServerProbe.UNREACHABLE
        HostHealth.UNKNOWN -> probeOf(true, service)
    }
