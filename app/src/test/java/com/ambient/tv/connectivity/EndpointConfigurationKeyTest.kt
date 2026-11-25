package com.ambient.tv.connectivity

import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class EndpointConfigurationKeyTest {
    @Test fun `all routing inputs invalidate synchronously without preference listeners`() {
        val preferences = memoryPreferences()
        val changes: List<(android.content.SharedPreferences.Editor) -> Unit> = listOf(
            { it.putString("server_host", "nas.local") },
            { it.putString("server_mac", "aabbccddeeff") },
            { it.putString("server_profiles_v1", "[]") },
            { it.putString("jellyfin_host", "nas.local") },
            { it.putString("jellyfin_mac", "aabbccddeeff") },
            { it.putString("jellyfin_server_profiles_v1", "[]") },
            { it.putString("emby_host", "nas.local") },
            { it.putString("emby_mac", "aabbccddeeff") },
            { it.putString("emby_server_profiles_v1", "[]") },
            { it.putString("tvh_host", "nas.local") },
            { it.putString("tvh_mac", "aabbccddeeff") },
            { it.putString("file_sources", "[]") },
            { it.putString("romm_server_url", "https://nas.local") },
            { it.putString("romm_auth_mode", "token") },
            { it.putBoolean("plex_enabled", false) },
            { it.putBoolean("jellyfin_enabled", true) },
            { it.putBoolean("emby_enabled", true) },
            { it.putBoolean("tvh_enabled", true) },
            { it.putBoolean("romm_enabled", true) },
            { it.putInt("server_port", 32401) },
            { it.putInt("jellyfin_port", 8097) },
            { it.putInt("emby_port", 8097) },
            { it.putInt("tvh_port", 9982) },
        )
        for (change in changes) {
            val before = EndpointConfigurationKey.read(preferences)
            preferences.edit().also(change).commit()
            assertNotEquals(before, EndpointConfigurationKey.read(preferences))
        }
    }

    @Test fun `unrelated settings do not invalidate and routing values are opaque`() {
        val preferences = memoryPreferences()
        preferences.edit().putString("file_sources", "private-account-marker").commit()
        val before = EndpointConfigurationKey.read(preferences)
        preferences.edit().putInt("game_library_grid_columns", 10).putString("accent_style", "ambient").commit()
        assertEquals(before, EndpointConfigurationKey.read(preferences))
        assertEquals("EndpointConfigurationKey", before.toString())
        assertFalse(before.toString().contains("private-account-marker"))
    }

    @Test fun `clearing settings invalidates existing topology`() {
        val preferences = memoryPreferences()
        preferences.edit().putString("server_host", "nas.local").commit()
        val before = EndpointConfigurationKey.read(preferences)
        preferences.edit().clear().commit()
        assertNotEquals(before, EndpointConfigurationKey.read(preferences))
    }

    @Test fun `malformed values can be invalidated without reading disabled provider configuration`() {
        val preferences = memoryPreferences()
        val before = EndpointConfigurationKey.read(preferences)
        preferences.edit().putString("jellyfin_port", "broken").commit()
        val malformed = EndpointConfigurationKey.read(preferences)
        assertNotEquals(before, malformed)
        assertEquals(malformed, EndpointConfigurationKey.read(preferences))
        preferences.edit().putInt("jellyfin_port", 8097).commit()
        assertNotEquals(malformed, EndpointConfigurationKey.read(preferences))
    }
}
