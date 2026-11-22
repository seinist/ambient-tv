package com.ambient.tv.companion

/** A live authenticated connection. Names are presentation only; the pinned identity is authoritative. */
data class CompanionConnectedPeer(val fingerprint: String, val name: String)

internal fun CompanionState.withConnectedPeer(session: String, peer: CompanionPeer): CompanionState {
    require(session.isNotBlank())
    val sessions = connectedSessions + (session to CompanionConnectedPeer(peer.fingerprint, peer.name))
    return copy(connectedSessions = sessions, connected = sessions.values.map { it.name }.distinct())
}

internal fun CompanionState.withDisconnectedPeer(session: String): CompanionState {
    val sessions = connectedSessions - session
    return copy(connectedSessions = sessions, connected = sessions.values.map { it.name }.distinct())
}
