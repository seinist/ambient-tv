package com.ambient.tv.companion

/**
 * One reachability answer per paired device, used by every list, picker and action guard.
 * WAKEABLE: not seen on the network, but Wake-on-LAN or WireGuard is configured, so connecting
 * wakes or tunnels to it and actions stay allowed. SEARCHING: a search is running and has not
 * found it yet. UNREACHABLE: nothing found and no way to wake it; only unpairing is allowed.
 */
enum class PeerReachability { CONNECTED, REACHABLE, WAKEABLE, SEARCHING, UNREACHABLE }

/** How long a device counts as reachable after the search last saw it or a session ended. */
internal const val COMPANION_SEEN_TTL_MS = 120_000L

internal fun companionReachability(
    fingerprint: String,
    state: CompanionState,
    wakeable: Boolean,
    nowMs: Long,
): PeerReachability = when {
    state.online && state.target?.fingerprint == fingerprint || fingerprint in state.syncPeers -> PeerReachability.CONNECTED
    state.devices.any { it.fingerprint == fingerprint } || fingerprint in state.sessions ||
        state.lastSeen[fingerprint]?.let { nowMs - it in 0 until COMPANION_SEEN_TTL_MS } == true -> PeerReachability.REACHABLE
    wakeable -> PeerReachability.WAKEABLE
    state.searching -> PeerReachability.SEARCHING
    else -> PeerReachability.UNREACHABLE
}

/** Connect, send playback, remote, transfer and share. Unpairing is never gated. */
internal fun PeerReachability.allowsActions(): Boolean =
    this == PeerReachability.CONNECTED || this == PeerReachability.REACHABLE || this == PeerReachability.WAKEABLE

/** A configured wake or tunnel path makes a sleeping TV actionable. */
internal fun companionWakeable(settings: CompanionConnectionSettings?): Boolean =
    settings != null && (settings.wireGuard && settings.valid() || settings.wake && validCompanionMac(settings.mac))

/**
 * Display name of a discovered TV: the advertised TXT `name` when present, else the DNS-SD
 * instance name without escapes, conflict suffix and the "Ambient TV " prefix.
 */
internal fun companionDisplayName(serviceName: String, advertised: String?): String {
    advertised?.let(::safeDeviceName)?.takeIf { it.isNotBlank() && advertised.isNotBlank() }?.let { return it }
    val unescaped = Regex("""\\(\d{3})""").replace(serviceName) { m -> m.groupValues[1].toInt().toChar().toString() }
        .replace("\\.", ".").replace("\\\\", "\\")
    val cleaned = unescaped.trim()
        .replace(Regex("""\s\(\d+\)$"""), "")
        .removePrefix("Ambient TV").trim()
    return safeDeviceName(cleaned.ifBlank { "TV" })
}

/** Warning shown before applying a transfer that was exported before this device's last import. */
enum class SyncTimestampHint { NONE, OLDER_THAN_LOCAL }

internal fun syncTimestampHint(incomingExportedAt: Long, localLastAppliedAt: Long): SyncTimestampHint =
    if (incomingExportedAt > 0 && localLastAppliedAt > 0 && incomingExportedAt < localLastAppliedAt) SyncTimestampHint.OLDER_THAN_LOCAL
    else SyncTimestampHint.NONE
