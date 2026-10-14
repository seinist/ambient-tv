package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionConnectionSettingsTest {
    @Test fun defaultsNeverEnableVpnOrWake() {
        val settings = companionJson.decodeFromString<CompanionConnectionSettings>("{}")
        assertTrue(settings.valid()); assertFalse(settings.wireGuard); assertFalse(settings.wake)
    }
    @Test fun vpnRequiresAnExplicitPrivateLiteral() {
        listOf("", "8.8.8.8", "127.0.0.1", "169.254.1.1", "tv.example", "192.168.1.300").forEach {
            assertFalse(it, CompanionConnectionSettings(wireGuard = true, vpnHost = it).valid())
        }
        assertTrue(CompanionConnectionSettings(wireGuard = true, vpnHost = "192.168.50.9").valid())
    }
    @Test fun invalidAndPlaceholderMacsAreRejected() {
        listOf("", "00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:22:33:44:55:66", "secret").forEach {
            assertFalse(it, validCompanionMac(it))
        }
        assertTrue(validCompanionMac("a0-b1-c2-d3-e4-f5"))
        assertEquals("A0:B1:C2:D3:E4:F5", canonicalCompanionMac("a0b1c2d3e4f5"))
    }
    @Test fun learningAMacDoesNotRequireOrEnableWake() {
        val settings = CompanionConnectionSettings(mac = "A0:B1:C2:D3:E4:F5")
        assertTrue(settings.valid()); assertFalse(settings.wake)
        assertFalse(CompanionConnectionSettings(wake = true).valid())
    }
    @Test fun invalidPortsAndExternalBroadcastsAreRejected() {
        assertFalse(CompanionConnectionSettings(port = 65536).valid())
        assertFalse(CompanionConnectionSettings(wakePort = 0).valid())
        assertFalse(CompanionConnectionSettings(broadcast = "8.8.8.8").valid())
        assertTrue(CompanionConnectionSettings(broadcast = "192.168.50.255").valid())
    }
    @Test fun offSubnetConnectionsAreTvOnlyAndPrivate() {
        assertTrue(companionAddressAllowed(false, true, "10.44.0.2"))
        assertFalse(companionAddressAllowed(false, false, "10.44.0.2"))
        assertFalse(companionAddressAllowed(false, true, "8.8.8.8"))
        assertTrue(companionAddressAllowed(true, false, "192.168.1.2"))
    }
    @Test fun duplicateStartCannotPlayTwiceOrChangeTheRequestedTitle() {
        val history = CompanionStartHistory()
        val request = CompanionFrame(type = "start", startRequestId = "request", media = CompanionMedia("youtube", "first"))
        assertFalse(history.alreadyCompleted("phone-a", request))
        history.complete("phone-a", request)
        assertTrue(history.alreadyCompleted("phone-a", request))
        assertFalse(history.alreadyCompleted("phone-b", request))
        assertThrows(IllegalArgumentException::class.java) { history.alreadyCompleted("phone-a", request.copy(media = CompanionMedia("youtube", "second"))) }
    }
    @Test fun wakeRoutesStayLimitedToTvAndDirectedBroadcast() {
        assertEquals(listOf("192.168.50.9/32", "192.168.50.255/32"),
            com.ambient.tv.vpn.tunnelRoutes(listOf("192.168.50.0/24"), listOf("192.168.50.9")))
    }
    @Test fun oldPeersAndFramesRemainDecodable() {
        val peer = companionJson.decodeFromString<CompanionPeer>("""{"fingerprint":"pin","name":"TV","host":"192.168.1.2","port":1234}""")
        assertEquals("192.168.1.2", peer.host)
        assertEquals("", companionJson.decodeFromString<CompanionFrame>("""{"type":"ready"}""").networkMac)
    }
}
