package com.ambient.tv.data.emby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyBackendTest {
    @Test
    fun `id adds provider prefix exactly once`() {
        assertEquals("e:item-42", EmbyBackend.id("item-42"))
        assertEquals("e:item-42", EmbyBackend.id("e:item-42"))
    }

    @Test
    fun `raw removes only the provider prefix`() {
        assertEquals("item-42", EmbyBackend.raw("e:item-42"))
        assertEquals("item-42", EmbyBackend.raw("item-42"))
    }

    @Test
    fun `owns recognizes only emby ids`() {
        assertTrue(EmbyBackend.owns("e:item-42"))
        assertFalse(EmbyBackend.owns("42"))
        assertFalse(EmbyBackend.owns("plex:item-42"))
        assertFalse(EmbyBackend.owns("j:item-42"))
    }
}
