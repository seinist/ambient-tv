package com.ambient.tv.vpn

import java.security.InvalidKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WgCryptoLowOrderTest {
    private val scalar = ByteArray(32) { (it * 7 + 1).toByte() }

    @Test
    fun `a low-order peer point is rejected instead of giving an all-zero secret`() {
        // u = 0 and u = 1 are small-order points on Curve25519: any scalar yields zero.
        assertThrows(InvalidKeyException::class.java) { WgCrypto.x25519(scalar, ByteArray(32)) }
        assertThrows(InvalidKeyException::class.java) { WgCrypto.x25519(scalar, ByteArray(32).also { it[0] = 1 }) }
    }

    @Test
    fun `an ordinary key agreement still works`() {
        val other = ByteArray(32) { (it * 3 + 5).toByte() }
        val shared = WgCrypto.x25519(scalar, WgCrypto.x25519(other))
        assertEquals(shared.toList(), WgCrypto.x25519(other, WgCrypto.x25519(scalar)).toList())
    }

    @Test
    fun `public keys and the shared secret match the RFC 7748 test vectors`() {
        // RFC 7748 section 6.1 (Alice and Bob).
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = WgCrypto.x25519(alicePrivate)
        val bobPublic = WgCrypto.x25519(bobPrivate)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePublic.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPublic.hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, WgCrypto.x25519(alicePrivate, bobPublic).hex())
        assertEquals(shared, WgCrypto.x25519(bobPrivate, alicePublic).hex())
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
