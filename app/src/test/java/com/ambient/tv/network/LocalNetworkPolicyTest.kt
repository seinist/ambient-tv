package com.ambient.tv.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPolicyTest {
    @Test fun privateIpv4RangesAreLocal() {
        assertTrue(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("172.16.5.4")))
        assertTrue(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("192.168.50.10")))
    }

    @Test fun carrierGradeNatRangeIsAccepted() {
        assertTrue(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("100.127.255.254")))
        assertFalse(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("100.128.0.1")))
    }

    @Test fun ulaIpv6IsLocalButPublicIpv6IsNot() {
        assertTrue(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("fd12:3456::1")))
        assertFalse(LocalNetworkPolicy.isLocalAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test fun publicIpv4IsNotLocal() {
        assertFalse(LocalNetworkPolicy.isLocalHost("8.8.8.8"))
    }

    @Test fun localConventionsRemainAccepted() {
        assertTrue(LocalNetworkPolicy.isLocalHost("localhost"))
        assertTrue(LocalNetworkPolicy.isLocalHost("plexbox.local"))
    }

    @Test
    fun `host decisions are cached and can be cleared`() {
        LocalNetworkPolicy.clearCache()
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.1.10"))
        assertTrue(LocalNetworkPolicy.isLocalHost("192.168.1.10"))
        assertFalse(LocalNetworkPolicy.isLocalHost("203.0.113.9"))
        LocalNetworkPolicy.clearCache()
        assertTrue(LocalNetworkPolicy.isLocalHost("[fd00::1]"))
    }
}
