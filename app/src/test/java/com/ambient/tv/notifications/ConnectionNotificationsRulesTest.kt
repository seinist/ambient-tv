package com.ambient.tv.notifications

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.server.ServerConnectionFailure
import com.ambient.tv.vpn.WireGuardRouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionNotificationsRulesTest {
    @Test
    fun onlyTypedVpnFailuresAreBlockedEvenWhenErrorTextMentionsVpn() {
        assertEquals(ConnectivityDependency.VPN, connectionDependency(
            ServerConnectionState.Error("Could not connect", ServerConnectionFailure.VPN),
            true, WireGuardRouteState.Failed("Could not connect"),
        ))
        assertEquals(null, connectionDependency(
            ServerConnectionState.Error("The server named VPN rejected this login"),
            true, WireGuardRouteState.Failed("Could not connect"),
        ))
        assertEquals(null, connectionDependency(ServerConnectionState.Offline, false, WireGuardRouteState.Connecting))
    }

    @Test
    fun connectingVpnBlocksOnlyItsPendingServices() {
        assertEquals(ConnectivityDependency.VPN, connectionDependency(ServerConnectionState.Checking, true, WireGuardRouteState.Connecting))
        assertEquals(null, connectionDependency(ServerConnectionState.Checking, false, WireGuardRouteState.Connecting))
        assertEquals(null, connectionDependency(ServerConnectionState.Error("Login failed"), true, WireGuardRouteState.Connecting))
    }

    @Test
    fun serviceStartingIsNoLongerAMachineWake() {
        assertTrue(isMachineWaking(ServerConnectionState.SendingWakePacket))
        assertTrue(isMachineWaking(ServerConnectionState.Waking(5, 60)))
        assertFalse(isMachineWaking(ServerConnectionState.Waking(5, 60, serviceStarting = true)))
        assertFalse(isMachineWaking(ServerConnectionState.Connecting))
    }

    @Test
    fun onlyTransitionsToOnlineCount() {
        val previous = mapOf("a" to HostHealth.OFFLINE, "b" to HostHealth.ONLINE, "c" to HostHealth.UNKNOWN)
        val current = mapOf("a" to HostHealth.ONLINE, "b" to HostHealth.ONLINE, "c" to HostHealth.OFFLINE, "d" to HostHealth.ONLINE)
        assertEquals(setOf("a", "d"), machinesNowOnline(previous, current).toSet())
    }
}
