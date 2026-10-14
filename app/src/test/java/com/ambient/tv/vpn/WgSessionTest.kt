package com.ambient.tv.vpn

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WgSessionTest {
    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Two sessions after a complete initiation/response handshake. */
    private fun pair(): Pair<WgSession, WgSession> {
        val a = key(); val b = key()
        val initiator = WgSession(a, WgCrypto.x25519(b))
        val responder = WgSession(b, WgCrypto.x25519(a))
        assertTrue(responder.consumeInitiation(initiator.initiation()))
        assertTrue(initiator.consumeResponse(responder.response()!!))
        return initiator to responder
    }

    private fun ipv4(source: String, payload: Int = 12): ByteArray = ByteArray(20 + payload).also { p ->
        p[0] = 0x45; p[2] = ((20 + payload) ushr 8).toByte(); p[3] = (20 + payload).toByte()
        source.split(".").forEachIndexed { i, part -> p[12 + i] = part.toInt().toByte() }
    }

    @Test
    fun `buffer based transport interoperates with the copying API`() {
        val (initiator, responder) = pair()
        val packet = ipv4("10.0.0.2")
        val buffer = packet.copyOf(1500)
        val sealed = ByteArray(1500 + 32)
        val length = initiator.sealPacketInto(buffer, packet.size, sealed)
        assertEquals(16 + packet.size + 16, length)
        assertArrayEquals(packet, responder.openPacket(sealed.copyOf(length)))

        val back = responder.sealPacket(packet)!!
        val inbound = back.copyOf(65535)
        val plain = ByteArray(65535)
        val size = initiator.openPacketInto(inbound, back.size, plain)
        assertEquals(packet.size, size)
        assertArrayEquals(packet, plain.copyOf(size))
        assertEquals(WgSession.TYPE_TRANSPORT, initiator.messageType(inbound, back.size))
    }

    @Test
    fun `replayed and tampered transport messages are rejected`() {
        val (initiator, responder) = pair()
        val sealed = ByteArray(128)
        val length = initiator.sealPacketInto(ipv4("10.0.0.2"), 32, sealed)
        val plain = ByteArray(128)
        assertEquals(32, responder.openPacketInto(sealed, length, plain))
        assertEquals(-1, responder.openPacketInto(sealed, length, plain))

        val next = initiator.sealPacketInto(ipv4("10.0.0.2"), 32, sealed)
        sealed[20] = (sealed[20].toInt() xor 1).toByte()
        assertEquals(-1, responder.openPacketInto(sealed, next, plain))
        assertEquals(-1, responder.openPacketInto(sealed, 31, plain))
    }

    @Test
    fun `keepalives seal to an empty payload`() {
        val (initiator, responder) = pair()
        val sealed = ByteArray(64)
        val length = initiator.sealPacketInto(ByteArray(0), 0, sealed)
        assertEquals(32, length)
        assertEquals(0, responder.openPacketInto(sealed, length, ByteArray(64)))
    }

    @Test
    fun `sealing without a session reports no packet`() {
        val session = WgSession(key(), WgCrypto.x25519(key()))
        assertEquals(-1, session.sealPacketInto(ByteArray(20), 20, ByteArray(64)))
        assertEquals(0, session.messageType(ByteArray(8), 3))
    }

    @Test
    fun `plaintext is padded to sixteen bytes but never beyond the mtu`() {
        assertEquals(0, WgSession.paddedLength(0, 1420))
        assertEquals(32, WgSession.paddedLength(21, 1420))
        assertEquals(48, WgSession.paddedLength(48, 1420))
        assertEquals(1420, WgSession.paddedLength(1419, 1420))
        assertEquals(1420, WgSession.paddedLength(1420, 1420))
    }

    @Test
    fun `a padded packet arrives with its original ipv4 length`() {
        val (initiator, responder) = pair()
        val packet = ipv4("10.0.0.2", payload = 13)
        val padded = WgSession.paddedLength(packet.size, 1420)
        val sealed = ByteArray(padded + 32)
        val length = initiator.sealPacketInto(packet.copyOf(padded), padded, sealed)
        val plain = ByteArray(1500)
        val size = responder.openPacketInto(sealed, length, plain)
        assertEquals(48, size)
        assertEquals(packet.size, inboundIpv4Length(plain, size, parseAllowedIps(listOf("10.0.0.0/24"))))
    }

    @Test
    fun `inbound length trims padding and checks allowed sources`() {
        val allowed = parseAllowedIps(listOf("10.0.0.0/24", "not-a-cidr"))
        val packet = ipv4("10.0.0.7").copyOf(64)
        assertEquals(32, inboundIpv4Length(packet, 64, allowed))
        assertEquals(-1, inboundIpv4Length(packet, 31, allowed))
        assertEquals(-1, inboundIpv4Length(ipv4("10.0.1.7"), 32, allowed))
        assertEquals(-1, inboundIpv4Length(ByteArray(40), 40, allowed))
    }
}
