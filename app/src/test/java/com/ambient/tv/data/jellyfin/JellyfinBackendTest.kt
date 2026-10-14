package com.ambient.tv.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinBackendTest {
    @Test
    fun `id adds provider prefix exactly once`() {
        assertEquals("j:item-42", JellyfinBackend.id("item-42"))
        assertEquals("j:item-42", JellyfinBackend.id("j:item-42"))
    }

    @Test
    fun `raw removes only the provider prefix`() {
        assertEquals("item-42", JellyfinBackend.raw("j:item-42"))
        assertEquals("item-42", JellyfinBackend.raw("item-42"))
    }

    @Test
    fun `owns recognizes only jellyfin ids`() {
        assertTrue(JellyfinBackend.owns("j:item-42"))
        assertFalse(JellyfinBackend.owns("42"))
        assertFalse(JellyfinBackend.owns("plex:item-42"))
    }
}
