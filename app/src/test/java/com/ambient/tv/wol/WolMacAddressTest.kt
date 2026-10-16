package com.ambient.tv.wol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WolMacAddressTest {
    @Test
    fun `continuous typing leaves cursor after each digit`() {
        var value = androidx.compose.ui.text.input.TextFieldValue("")
        for (digit in "aabbccddeeff") {
            val cursor = value.selection.end
            val text = value.text.substring(0, cursor) + digit + value.text.substring(cursor)
            value = formatMacAddressInput(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(cursor + 1)))
            assertEquals(value.text.length, value.selection.end)
        }
        assertEquals("AA:BB:CC:DD:EE:FF", value.text)
    }

    @Test
    fun `paste maps selection past inserted separators`() {
        val value = formatMacAddressInput(androidx.compose.ui.text.input.TextFieldValue("aabbccddeeff", androidx.compose.ui.text.TextRange(12)))
        assertEquals("AA:BB:CC:DD:EE:FF", value.text)
        assertEquals(17, value.selection.end)
    }

    @Test
    fun `editing in the middle preserves cursor and reversed selection`() {
        val value = formatMacAddressInput(androidx.compose.ui.text.input.TextFieldValue("AA:cBB", androidx.compose.ui.text.TextRange(4)))
        assertEquals("AA:CB:B", value.text)
        assertEquals(4, value.selection.end)
        val selected = formatMacAddressInput(androidx.compose.ui.text.input.TextFieldValue("aabbcc", androidx.compose.ui.text.TextRange(6, 2)))
        assertEquals(androidx.compose.ui.text.TextRange(8, 2), selected.selection)
    }

    @Test
    fun `backspace and invalid characters keep cursor in bounds`() {
        val deleted = formatMacAddressInput(androidx.compose.ui.text.input.TextFieldValue("AA:", androidx.compose.ui.text.TextRange(3)))
        assertEquals("AA", deleted.text)
        assertEquals(2, deleted.selection.end)
        val invalid = formatMacAddressInput(androidx.compose.ui.text.input.TextFieldValue("AA:z", androidx.compose.ui.text.TextRange(4)))
        assertEquals("AA", invalid.text)
        assertEquals(2, invalid.selection.end)
    }

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
