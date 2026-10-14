package com.ambient.tv.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WgReplayWindowTest {
    @Test
    fun `duplicates are rejected and reordering inside the window is accepted`() {
        val window = WgReplayWindow(2048)
        assertTrue(window.markAccepted(0))
        assertFalse(window.markAccepted(0))
        assertTrue(window.markAccepted(5))
        assertTrue(window.markAccepted(3))
        assertFalse(window.markAccepted(3))
        assertFalse(window.canAccept(-1))
    }

    @Test
    fun `counters older than the window are rejected after it slides`() {
        val window = WgReplayWindow(2048)
        assertTrue(window.markAccepted(10))
        assertTrue(window.markAccepted(3000))
        assertFalse(window.canAccept(10))
        assertFalse(window.canAccept(3000 - 2048))
        assertTrue(window.canAccept(3000 - 2047))
        // Slots reused after sliding are free again for new, in-window counters.
        assertTrue(window.markAccepted(3000 - 2047))
        assertTrue(window.markAccepted(5048))
        assertTrue(window.canAccept(5047))
        assertFalse(window.canAccept(3000))
    }

    @Test
    fun `small steps clear exactly the slots they pass`() {
        val window = WgReplayWindow(128)
        for (counter in 0L..127L) assertTrue(window.markAccepted(counter))
        // 128 shares slot 0 with counter 0; 0 is now out of the window and 128 is new.
        assertTrue(window.markAccepted(128))
        assertFalse(window.canAccept(0))
        assertFalse(window.canAccept(127))
        assertTrue(window.markAccepted(130))
        assertTrue(window.canAccept(129))
    }

    @Test
    fun `reused ciphers keep sealing and opening per packet`() {
        val key = ByteArray(32) { it.toByte() }
        repeat(3) { counter ->
            val nonce = ByteArray(12).also { it[4] = counter.toByte() }
            val plain = "packet $counter".encodeToByteArray()
            val sealed = WgCrypto.seal(key, nonce, ByteArray(0), plain)
            assertArrayEquals(plain, WgCrypto.open(key, nonce, ByteArray(0), sealed))
            sealed[0] = (sealed[0].toInt() xor 1).toByte()
            assertNull(WgCrypto.open(key, nonce, ByteArray(0), sealed))
        }
    }
}
