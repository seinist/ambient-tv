package com.ambient.tv.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyConnectPolicyTest {
    @Test
    fun `default name carries the device name`() {
        assertEquals("Ambient TV · Wohnzimmer", defaultSpotifyDeviceName("Ambient TV", " Wohnzimmer "))
        assertEquals("Ambient TV · SHIELD Android TV", defaultSpotifyDeviceName("Ambient TV", "SHIELD Android TV"))
        assertEquals("Ambient TV", defaultSpotifyDeviceName("Ambient TV", null))
        assertEquals("Ambient TV", defaultSpotifyDeviceName("Ambient TV", "  "))
        assertEquals("Ambient TV", defaultSpotifyDeviceName("Ambient TV", "ambient tv"))
        assertEquals(40, defaultSpotifyDeviceName("A", "x".repeat(80)).removePrefix("A · ").length)
    }

    @Test
    fun `only a definitively rejected refresh token ends the session`() {
        assertTrue(spotifyRefreshFailureEndsSession(400, "invalid_grant"))
        assertTrue(spotifyRefreshFailureEndsSession(401, "invalid_client"))
        assertFalse(spotifyRefreshFailureEndsSession(400, "invalid_request"))
        assertFalse(spotifyRefreshFailureEndsSession(400, null))
        assertFalse(spotifyRefreshFailureEndsSession(503, "invalid_grant"))
    }

    @Test
    fun `playback device is the own id or an unambiguous name`() {
        val devices = listOf("a1" to "Ambient TV", "b2" to "Ambient TV", "c3" to "Kitchen")
        assertEquals("b2", pickSpotifyPlaybackDevice(devices, "b2", "Ambient TV"))
        assertNull(pickSpotifyPlaybackDevice(devices, "zz", "Ambient TV"))
        assertEquals("c3", pickSpotifyPlaybackDevice(devices, null, "kitchen"))
        assertNull(pickSpotifyPlaybackDevice(emptyList(), "a1", "Ambient TV"))
    }

    @Test
    fun `receiver restarts only after a real network move`() {
        val home = spotifyNetworkKey(100, listOf("192.168.1.20"))
        assertEquals(home, spotifyNetworkKey(100, listOf("192.168.1.20", "")))
        assertFalse(spotifyNetworkChangeNeedsRestart(null, home, hasIpv4 = true))
        assertFalse(spotifyNetworkChangeNeedsRestart(home, home, hasIpv4 = true))
        assertTrue(spotifyNetworkChangeNeedsRestart(home, spotifyNetworkKey(100, listOf("192.168.1.31")), hasIpv4 = true))
        assertTrue(spotifyNetworkChangeNeedsRestart(home, spotifyNetworkKey(101, listOf("192.168.1.20")), hasIpv4 = true))
        // A network without IPv4 yet (DHCP still running) waits for its address.
        assertFalse(spotifyNetworkChangeNeedsRestart(home, spotifyNetworkKey(101, emptyList()), hasIpv4 = false))
    }
}
