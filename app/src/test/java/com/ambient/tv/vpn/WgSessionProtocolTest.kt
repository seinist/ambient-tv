package com.ambient.tv.vpn

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class WgSessionProtocolTest {
    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var now = 1_000_000L

    private fun putInt(target: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) target[at + i] = (value ushr (8 * i)).toByte()
    }
    private fun intAt(source: ByteArray, at: Int) =
        (0 until 4).fold(0) { acc, i -> acc or ((source[at + i].toInt() and 255) shl (8 * i)) }

    /** What a loaded responder sends: a cookie sealed with the mac1 of [initiation] as AAD. */
    private fun cookieReply(responderPublic: ByteArray, initiation: ByteArray, cookie: ByteArray, aad: ByteArray = initiation.copyOfRange(116, 132)): ByteArray {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val key = WgCrypto.blake2s("cookie--".encodeToByteArray() + responderPublic)
        val sealed = WgCrypto.sealXChaCha20Poly1305(key, nonce, aad, cookie)
        return ByteArray(64).also { msg ->
            putInt(msg, 0, WgSession.TYPE_COOKIE)
            putInt(msg, 4, intAt(initiation, 4))
            System.arraycopy(nonce, 0, msg, 8, 24)
            System.arraycopy(sealed, 0, msg, 32, 32)
        }
    }

    @Test fun cookieReplyIsAuthenticatedWithMac1AndUsedForMac2() {
        val a = key(); val b = key(); val bPublic = WgCrypto.x25519(b)
        val initiator = WgSession(a, bPublic) { now }
        val first = initiator.initiation()
        val cookie = ByteArray(16) { it.toByte() }
        // Empty AAD (the old behaviour) must not authenticate.
        assertFalse(initiator.consumeCookieReply(cookieReply(bPublic, first, cookie, aad = ByteArray(0))))
        assertTrue(initiator.consumeCookieReply(cookieReply(bPublic, first, cookie)))
        val second = initiator.initiation()
        val expectedMac2 = WgCrypto.blake2s(second.copyOf(second.size - 16), cookie, 16)
        assertArrayEquals(expectedMac2, second.copyOfRange(132, 148))
    }

    @Test fun cookieExpiresAfter120Seconds() {
        val a = key(); val b = key(); val bPublic = WgCrypto.x25519(b)
        val initiator = WgSession(a, bPublic) { now }
        val first = initiator.initiation()
        assertTrue(initiator.consumeCookieReply(cookieReply(bPublic, first, ByteArray(16) { 7 })))
        now += WgSession.COOKIE_MAX_AGE_MS
        val later = initiator.initiation()
        assertArrayEquals(ByteArray(16), later.copyOfRange(132, 148))
    }

    @Test fun keysAreRejectedAfter180Seconds() {
        val a = key(); val b = key()
        val initiator = WgSession(a, WgCrypto.x25519(b)) { now }
        val responder = WgSession(b, WgCrypto.x25519(a)) { now }
        assertTrue(responder.consumeInitiation(initiator.initiation()))
        assertTrue(initiator.consumeResponse(responder.response()!!))
        assertNotNull(responder.openPacket(initiator.sealPacket(ByteArray(0))!!))
        now += WgSession.REJECT_AFTER_TIME_MS
        assertFalse(initiator.established)
        assertNull(initiator.sealPacket(ByteArray(0)))
    }

    @Test fun responderKeypairWaitsForTheInitiatorsFirstPacket() {
        val a = key(); val b = key()
        val initiator = WgSession(a, WgCrypto.x25519(b)) { now }
        val responder = WgSession(b, WgCrypto.x25519(a)) { now }
        assertTrue(responder.consumeInitiation(initiator.initiation()))
        val response = responder.response()!!
        assertFalse(responder.established)
        assertNull(responder.sealPacket(ByteArray(0)))
        assertTrue(initiator.consumeResponse(response))
        assertNotNull(responder.openPacket(initiator.sealPacket(ByteArray(0))!!))
        assertTrue(responder.established)
        assertNotNull(initiator.openPacket(responder.sealPacket(ByteArray(0))!!))
    }

    @Test fun lowOrderEphemeralIsRejectedNotThrown() {
        val a = key(); val b = key()
        val initiator = WgSession(a, WgCrypto.x25519(b)) { now }
        val responder = WgSession(b, WgCrypto.x25519(a)) { now }
        val forged = initiator.initiation().also { msg ->
            for (i in 8 until 40) msg[i] = 0
            val mac1Key = WgCrypto.blake2s("mac1----".encodeToByteArray() + WgCrypto.x25519(b))
            System.arraycopy(WgCrypto.blake2s(msg.copyOf(116), mac1Key, 16), 0, msg, 116, 16)
        }
        assertFalse(responder.consumeInitiation(forged))
    }
}
