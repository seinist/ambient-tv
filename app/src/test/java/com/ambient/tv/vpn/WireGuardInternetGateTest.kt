package com.ambient.tv.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardInternetGateTest {
    @Test fun remoteTunnelDoesNotStartWithoutValidatedInternet() {
        assertFalse(shouldAttemptWireGuardTunnel(
            validatedInternet = false, localPeer = false, established = false))
    }

    @Test fun normalConnectionStartsWhenInternetIsAvailable() {
        assertTrue(shouldAttemptWireGuardTunnel(
            validatedInternet = true, localPeer = false, established = false))
    }

    @Test fun localTunnelCanStillWorkOnOfflineLan() {
        assertTrue(shouldAttemptWireGuardTunnel(
            validatedInternet = false, localPeer = true, established = false))
    }

    @Test fun existingTunnelIsNotDisconnectedWhenInternetGoesAway() {
        assertTrue(shouldAttemptWireGuardTunnel(
            validatedInternet = false, localPeer = false, established = true))
    }
}
