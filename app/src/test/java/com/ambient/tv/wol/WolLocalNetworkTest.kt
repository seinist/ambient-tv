package com.ambient.tv.wol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WolLocalNetworkTest {
    private fun ip(value: String) = value.split('.').map { it.toInt().toByte() }.toByteArray()
    private val homeWifi = listOf(ip("192.168.178.23") to 24)

    @Test fun privateAddressesAreRecognised() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.5", "169.254.10.1")
            .forEach { assertTrue(it, privateIpv4Bytes(it) != null) }
        listOf("8.8.8.8", "172.32.0.1", "nas.local", "192.168.1", "300.1.1.1", "fe80::1")
            .forEach { assertNull(it, privateIpv4Bytes(it)) }
    }

    @Test fun serverInTheSameSubnetCanBeWoken() {
        assertTrue(wolLocalBroadcastCanReach("192.168.178.40", "255.255.255.255", false, homeWifi))
    }

    @Test fun privateServerOutsideEveryLocalNetworkIsNotWoken() {
        // On mobile data (no LAN), or in another Wi-Fi.
        assertFalse(wolLocalBroadcastCanReach("192.168.178.40", "255.255.255.255", false, emptyList()))
        assertFalse(wolLocalBroadcastCanReach("192.168.178.40", "255.255.255.255", false,
            listOf(ip("10.0.0.7") to 8)))
    }

    @Test fun uncertainCasesStillWake() {
        assertTrue(wolLocalBroadcastCanReach("nas.local", "255.255.255.255", false, emptyList()))
        assertTrue(wolLocalBroadcastCanReach("85.1.2.3", "255.255.255.255", false, emptyList()))
        assertTrue(wolLocalBroadcastCanReach("192.168.2.10", "192.168.2.255", false, homeWifi))
        assertTrue(wolLocalBroadcastCanReach("192.168.178.40", "255.255.255.255", true, emptyList()))
        assertTrue(wolLocalBroadcastCanReach(null, "255.255.255.255", false, emptyList()))
    }

    @Test fun subnetMaskBoundaries() {
        assertTrue(ipv4InSubnet(ip("10.200.1.1"), ip("10.0.0.1"), 8))
        assertFalse(ipv4InSubnet(ip("10.200.1.1"), ip("10.0.0.1"), 16))
        assertTrue(ipv4InSubnet(ip("172.20.5.9"), ip("172.20.0.1"), 20))
        assertFalse(ipv4InSubnet(ip("172.20.16.9"), ip("172.20.0.1"), 20))
    }
}
