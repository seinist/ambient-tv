package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionProbeTest {
    private val tv = CompanionPeer("b".repeat(64), "TV", "192.168.1.20", 4000, role = COMPANION_TV)
    private val base = CompanionState(paired = listOf(tv))
    private val now = 1_000_000L

    private fun needs(state: CompanionState = base, vpn: Boolean = false, lan: Boolean = true, last: Long? = null, peer: CompanionPeer = tv) =
        companionNeedsProbe(peer, state, vpn, lan, last, now)

    @Test fun unseenLanPeerIsProbed() = assertTrue(needs())

    @Test fun noProbeWhenAlreadyKnownOrConnected() {
        assertFalse(needs(base.copy(devices = listOf(tv))))
        assertFalse(needs(base.copy(online = true, target = tv)))
        assertFalse(needs(base.copy(syncPeers = mapOf(tv.fingerprint to emptyList()))))
        assertFalse(needs(base.copy(lastSeen = mapOf(tv.fingerprint to now - 10_000))))
        assertFalse(needs(base.copy(probing = setOf(tv.fingerprint))))
    }

    @Test fun noProbeAcrossWireGuardOrOtherNetworks() {
        assertFalse(needs(vpn = true))
        assertFalse(needs(lan = false))
        assertFalse(needs(peer = tv.copy(host = "8.8.8.8")))
        assertFalse(needs(peer = tv.copy(port = 0)))
    }

    @Test fun probeIsRateLimited() {
        assertFalse(needs(last = now - 5_000))
        assertTrue(needs(last = now - COMPANION_PROBE_INTERVAL_MS))
    }

    @Test fun reachabilityFollowsProbe() {
        // Probe running: still searching, not yet unreachable.
        assertEquals(PeerReachability.SEARCHING, companionReachability(tv.fingerprint, base.copy(probing = setOf(tv.fingerprint)), false, now))
        // Probe succeeded: lastSeen set -> reachable for the TTL.
        val seen = base.copy(lastSeen = mapOf(tv.fingerprint to now))
        assertEquals(PeerReachability.REACHABLE, companionReachability(tv.fingerprint, seen, false, now))
        assertEquals(PeerReachability.UNREACHABLE, companionReachability(tv.fingerprint, seen, false, now + COMPANION_SEEN_TTL_MS))
        // mDNS and probe both failed.
        assertEquals(PeerReachability.UNREACHABLE, companionReachability(tv.fingerprint, base, false, now))
        // Wake path still wins over a running probe.
        assertEquals(PeerReachability.WAKEABLE, companionReachability(tv.fingerprint, base.copy(probing = setOf(tv.fingerprint)), true, now))
    }
}
