package com.ambient.tv.data.local

import com.ambient.tv.domain.model.ServerProfile
import org.junit.Assert.*
import org.junit.Test

class PlexTokenEndpointTest {
    @Test fun plexTokenIsBoundToTheActiveProfileAddress() {
        val prefs = AppPreferences(memoryPreferences())
        val tokens = TokenStore(memoryPreferences(), prefs)
        prefs.upsertServerProfile(ServerProfile(id = "p1", name = "Plex", host = "192.168.1.10"), makeActive = true)
        tokens.token = "secret"
        assertEquals("secret", tokens.token)
        prefs.host = "203.0.113.5"
        assertNull(tokens.token)
        prefs.host = "192.168.1.10"
        assertEquals("secret", tokens.token)
        prefs.plexUseHttps = true
        assertNull(tokens.token)
    }
}
