package com.ambient.tv.notifications

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.server.ServerConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionNotificationsRulesTest {
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
