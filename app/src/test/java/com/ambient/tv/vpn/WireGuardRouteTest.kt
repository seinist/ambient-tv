package com.ambient.tv.vpn

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardRouteTest {
    @Test fun blake2sKnownAnswers() {
        assertEquals(
            "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
            WgCrypto.blake2s(ByteArray(0)).toHex(),
        )
        assertEquals(
            "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
            WgCrypto.blake2s("abc".encodeToByteArray()).toHex(),
        )
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(64) { it.toByte() }
        assertEquals(
            "8975b0577fd35566d750b362b0897a26c399136df07bababbde6203ff2954ed4",
            WgCrypto.blake2s(data, key).toHex(),
        )
    }

    @Test fun hmacBlake2sKnownAnswers() {
        assertEquals(
            "1b3b81abdc97a097da15fce4e261a1199ad81f9aa193a97a3eaeb4d770bbf6dd",
            WgCrypto.hmacBlake2s("k".repeat(32).encodeToByteArray(), "data".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "e2641d24dfa8dd89e8cb77f1cdc77d8c006e5bebdfc4d6a3a3a6f8d5b7586fb0",
            WgCrypto.hmacBlake2s(ByteArray(32) { it.toByte() }, ByteArray(0)).toHex(),
        )
    }

    @Test fun x25519Rfc7748() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val point = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            WgCrypto.x25519(scalar, point).toHex(),
        )
    }

    @Test fun handshakeRoundTrip() {
        val initiatorPrivate = ByteArray(32) { (it + 1).toByte() }
        val responderPrivate = ByteArray(32) { (it + 33).toByte() }
        val initiator = WgSession(initiatorPrivate, WgCrypto.x25519(responderPrivate))
        val responder = WgSession(responderPrivate, WgCrypto.x25519(initiatorPrivate))
        val opening = initiator.initiation(1_700_000_000_000)
        assertEquals(148, opening.size)
        assertTrue(responder.consumeInitiation(opening))
        val reply = responder.response()!!
        assertEquals(92, reply.size)
        assertTrue(initiator.consumeResponse(reply))
        val sealed = initiator.sealPacket("plex".encodeToByteArray())!!
        assertArrayEquals("plex".encodeToByteArray(), responder.openPacket(sealed))
        val back = responder.sealPacket(ByteArray(0))!!
        assertEquals(0, initiator.openPacket(back)!!.size)
    }

    @Test fun cookieReplyAddsMac2ToNextHandshake() {
        val initiatorPrivate = ByteArray(32) { (it + 1).toByte() }
        val responderPrivate = ByteArray(32) { (it + 33).toByte() }
        val peerPublic = WgCrypto.x25519(responderPrivate)
        val initiator = WgSession(initiatorPrivate, peerPublic)
        val first = initiator.initiation(1_700_000_000_000)
        val receiverIndex = (first[4].toInt() and 255) or ((first[5].toInt() and 255) shl 8) or
            ((first[6].toInt() and 255) shl 16) or ((first[7].toInt() and 255) shl 24)
        val nonce = ByteArray(24) { it.toByte() }
        val cookie = ByteArray(16) { (it + 9).toByte() }
        val key = WgCrypto.blake2s("cookie--".encodeToByteArray() + peerPublic)
        // The cookie reply is authenticated with the mac1 of the handshake it answers.
        val encrypted = WgCrypto.sealXChaCha20Poly1305(key, nonce, first.copyOfRange(116, 132), cookie)
        val reply = ByteArray(64)
        reply[0] = WgSession.TYPE_COOKIE.toByte()
        repeat(4) { i -> reply[4 + i] = (receiverIndex ushr (8 * i)).toByte() }
        System.arraycopy(nonce, 0, reply, 8, 24)
        System.arraycopy(encrypted, 0, reply, 32, 32)
        assertTrue(initiator.consumeCookieReply(reply))
        val second = initiator.initiation(1_700_000_001_000)
        assertFalse(second.copyOfRange(132, 148).contentEquals(ByteArray(16)))
    }

    @Test fun replayIsRejectedAndRekeyKeepsPreviousReceiverIndex() {
        val initiatorPrivate = ByteArray(32) { (it + 1).toByte() }
        val responderPrivate = ByteArray(32) { (it + 33).toByte() }
        val initiator = WgSession(initiatorPrivate, WgCrypto.x25519(responderPrivate))
        val responder = WgSession(responderPrivate, WgCrypto.x25519(initiatorPrivate))
        assertTrue(responder.consumeInitiation(initiator.initiation(1_700_000_000_000)))
        assertTrue(initiator.consumeResponse(responder.response()!!))
        val first = initiator.sealPacket("first".encodeToByteArray())!!
        val late = initiator.sealPacket("late".encodeToByteArray())!!
        assertArrayEquals("first".encodeToByteArray(), responder.openPacket(first))
        assertNull(responder.openPacket(first))

        assertTrue(responder.consumeInitiation(initiator.initiation(1_700_000_003_000)))
        val response = responder.response()!!
        assertEquals(WgSession.TYPE_RESPONSE, initiator.messageType(response))
        assertTrue(initiator.established)
        assertTrue(initiator.consumeResponse(response))
        assertArrayEquals("late".encodeToByteArray(), responder.openPacket(late))
        assertNull(responder.openPacket(late))
    }

    @Test fun inboundCryptokeyRoutingChecksSourceAndLength() {
        val packet = ByteArray(24)
        packet[0] = 0x45
        packet[2] = 0
        packet[3] = 20
        packet[12] = 10; packet[13] = 0; packet[14] = 0; packet[15] = 7
        assertEquals(20, validateInboundIpv4(packet, listOf("10.0.0.0/24"))!!.size)
        assertNull(validateInboundIpv4(packet, listOf("10.1.0.0/24")))
        packet[0] = 0x60
        assertNull(validateInboundIpv4(packet, listOf("10.0.0.0/24")))
    }

    @Test fun parserKeepsTheFirstPeerWithAnEndpointAndRejectsIpv6OnlyAddresses() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val base = """
            [Interface]
            PrivateKey = $secret
            Address = 10.8.0.2/32
            [Peer]
            PublicKey = $secret
            Endpoint = 203.0.113.10:51820
            AllowedIPs = 192.168.1.0/24
        """.trimIndent()
        val two = parseWireGuardConfig(base + "\n[Peer]\nPublicKey = $secret\nEndpoint = 203.0.113.11:51820\nAllowedIPs = 10.0.0.0/8")
        assertEquals("203.0.113.10", two.profile.endpointHost)
        assertEquals(1, two.ignoredPeers)
        assertTrue(runCatching { parseWireGuardConfig(base.replace("10.8.0.2/32", "fd00::2/128")) }.isFailure)
    }

    @Test fun fritzBoxConfigWithSearchDomainAndAppKeysImports() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val parsed = parseWireGuardConfig(
            """
            [Interface]
            Address = 192.168.178.195/24, fd00::195/64
            DNS = 192.168.178.1, fritz.box
            ExcludedApplications = com.google.android.projection.gearhead
            PrivateKey = $secret

            [Peer]
            AllowedIPs = 192.168.178.0/24, 0.0.0.0/0, ::/0
            Endpoint = example.myfritz.net:51820
            PersistentKeepalive = 25
            PreSharedKey = $secret
            PublicKey = $secret
            """.trimIndent(),
        )
        assertEquals("192.168.178.195", parsed.profile.address)
        assertEquals(24, parsed.profile.prefix)
        assertEquals("192.168.178.1", parsed.profile.dns)
        assertEquals(listOf("192.168.178.0/24", "0.0.0.0/0"), parsed.profile.allowedIps)
        assertEquals("example.myfritz.net", parsed.profile.endpointHost)
        assertEquals(secret, parsed.presharedKey)
        val missingKey = runCatching { parseWireGuardConfig("[Interface]\nAddress = 10.0.0.2/32\nPrivateKey = \n[Peer]\nPublicKey = $secret\nEndpoint = h:1\nAllowedIPs = 10.0.0.0/8") }
        assertEquals("PrivateKey is missing", missingKey.exceptionOrNull()?.message)
    }

    @Test fun splitRoutesStayScopedToIndividuallyEnabledSystems() {
        val allowed = listOf("10.1.1.0/24", "10.2.2.0/24", "10.3.3.0/24")
        assertEquals(
            listOf("10.3.3.30/32", "10.3.3.255/32"),
            tunnelRoutes(allowed, listOf("10.3.3.30")),
        )
        assertEquals(
            listOf("10.1.1.10/32", "10.1.1.255/32", "10.3.3.30/32", "10.3.3.255/32"),
            tunnelRoutes(allowed, listOf("10.1.1.10", "10.3.3.30")),
        )
        val tvhWake = wakeTargetsFor(allowed, listOf("10.3.3.30"))
        assertTrue(tvhWake.contains("10.3.3.30"))
        assertTrue(tvhWake.contains("10.3.3.255"))
        assertFalse(tvhWake.any { it.startsWith("10.1.1.") || it.startsWith("10.2.2.") })
    }

    @Test fun configParseAndWakeTargets() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val parsed = parseWireGuardConfig(
            """
            [Interface]
            PrivateKey = $secret
            Address = 10.8.0.2/32
            DNS = 10.8.0.1
            [Peer]
            PublicKey = $secret
            Endpoint = 203.0.113.10:51820
            AllowedIPs = 192.168.1.0/24
            PersistentKeepalive = 25
            """.trimIndent(),
        )
        assertEquals("10.8.0.2", parsed.profile.address)
        assertEquals("203.0.113.10", parsed.profile.endpointHost)
        assertEquals(listOf("192.168.1.0/24"), parsed.profile.allowedIps)
        assertNull(parsed.presharedKey)
        val targets = wakeTargetsFor(parsed.profile.allowedIps, listOf("192.168.1.20", "10.0.0.5", "nas.local"))
        assertTrue(targets.contains("192.168.1.255"))
        assertTrue(targets.contains("192.168.1.20"))
        assertFalse(targets.contains("10.0.0.5"))
        assertEquals(listOf("192.168.50.8"), wakeTargetsFor(listOf("192.168.50.8/32"), listOf("192.168.50.8")))
        assertTrue(wakeTargetsFor(listOf("10.0.0.0/7"), listOf("10.1.1.1")).none { it.endsWith(".255") })
        val narrowed = wakeTargetsFor(listOf("192.168.1.0/24", "10.9.0.0/24"), listOf("192.168.1.20"))
        assertTrue(narrowed.contains("192.168.1.255"))
        assertFalse(narrowed.contains("10.9.0.255"))
        assertEquals(listOf("192.168.1.20/32", "192.168.1.255/32"), tunnelRoutes(listOf("192.168.1.0/24"), listOf("192.168.1.20", "10.0.0.5")))
        assertEquals(listOf("192.168.1.0/24"), tunnelRoutes(listOf("192.168.1.0/24"), listOf("nas.local")))
        assertFalse(hostCovered(listOf("192.168.1.0/24"), "10.0.0.5"))
        assertTrue(hostCovered(listOf("192.168.1.0/24"), "192.168.1.20"))
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
