package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionConnectionStatusTest {
    @Test fun oldSocketCleanupCannotMarkReconnectedPhoneOffline() {
        val peer = CompanionPeer("pinned-identity", "Phone", role = COMPANION_HANDHELD)
        val state = CompanionState().withConnectedPeer("old-session", peer)
            .withConnectedPeer("new-session", peer.copy(name = "Renamed phone"))
            .withDisconnectedPeer("old-session")
        assertEquals(setOf(peer.fingerprint), state.connectedSessions.values.map { it.fingerprint }.toSet())
        assertEquals(listOf("Renamed phone"), state.connected)
        assertTrue(state.withDisconnectedPeer("new-session").connectedSessions.isEmpty())
    }

    @Test fun disconnectingOneOfTwoSameNamePhonesKeepsTheOtherIdentity() {
        val a = CompanionPeer("a", "Phone", role = COMPANION_HANDHELD)
        val b = a.copy(fingerprint = "b")
        val state = CompanionState().withConnectedPeer("tls-a", a).withConnectedPeer("tls-b", b)
            .withDisconnectedPeer("tls-a")
        assertEquals(listOf("b"), state.connectedSessions.values.map { it.fingerprint })
        assertEquals(listOf("Phone"), state.connected)
        assertEquals(PeerReachability.UNREACHABLE, companionReachability("a", state, false, 1_000))
        assertEquals(PeerReachability.CONNECTED, companionReachability("b", state, false, 1_000))
        assertEquals(state, state.withDisconnectedPeer("already-closed"))
    }
}
