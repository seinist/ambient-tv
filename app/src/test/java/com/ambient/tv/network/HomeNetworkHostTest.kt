package com.ambient.tv.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNetworkHostTest {
    @Test
    fun `home network names are recognised without DNS`() {
        listOf("192.168.1.10", "10.0.0.5", "nas.local", "plexserver", "[fd00::10]",
            "192-168-1-10.0123abcd.plex.direct", "localhost").forEach { assertTrue(it, isHomeNetworkHost(it)) }
        listOf("plex.example.com", "203.0.113.7", "203-0-113-7.0123abcd.plex.direct", "")
            .forEach { assertFalse(it, isHomeNetworkHost(it)) }
    }
}
