package com.ambient.tv.wol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WolMacAddressTest {
    @Test
    fun `typing and pasting MACs uses the same format`() {
        assertEquals("", formatMacAddressInput(""))
        assertEquals("A", formatMacAddressInput("a"))
        assertEquals("AA:B", formatMacAddressInput("aab"))
        assertEquals("AA:BB:CC:DD:EE:FF", formatMacAddressInput("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", formatMacAddressInput("aabb.ccdd.eeff"))
        assertEquals("AA:BB:CC:DD:EE:FF", formatMacAddressInput("aabbccddeeff00"))
        assertEquals("12", formatMacAddressInput("g1z2３"))
        assertEquals("AA:B", formatMacAddressInput("AA:B"))
    }

    @Test
    fun `equivalent MAC spellings share one canonical key`() {
        val expected = "aabbccddeeff"
        assertEquals(expected, canonicalWolMacKey("AA:BB:CC:DD:EE:FF"))
        assertEquals(expected, canonicalWolMacKey("aa-bb-cc-dd-ee-ff"))
        assertEquals(expected, canonicalWolMacKey("AABBCCDDEEFF"))
        assertEquals(expected, canonicalWolMacKey("aabb.ccdd.eeff"))
    }

    @Test
    fun `invalid MAC addresses are rejected`() {
        assertNull(canonicalWolMacKey(null))
        assertNull(canonicalWolMacKey(""))
        assertNull(canonicalWolMacKey("AA:BB:CC:DD:EE"))
        assertNull(canonicalWolMacKey("GG:BB:CC:DD:EE:FF"))
    }
}
