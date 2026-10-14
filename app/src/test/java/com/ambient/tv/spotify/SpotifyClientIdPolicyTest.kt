package com.ambient.tv.spotify

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SpotifyClientIdPolicyTest {
    @Test fun onlyTheStoredIdIsUsed() {
        assertEquals("", SpotifyClientIdPolicy.effective(null))
        assertEquals("", SpotifyClientIdPolicy.effective("   "))
        assertFalse(SpotifyClientIdPolicy.configured(""))
        assertEquals("abc123", SpotifyClientIdPolicy.effective("  abc123 \n"))
        assertTrue(SpotifyClientIdPolicy.configured("abc123"))
    }

    @Test fun buildDoesNotBakeInAClientId() {
        assertFalse(File("build.gradle.kts").readText().contains("SPOTIFY_CLIENT_ID"))
    }

    @Test fun missingIdHintPointsToTheReadmeGuide() {
        for (locale in listOf("values", "values-en", "values-de")) {
            val text = File("src/main/res/$locale/strings.xml").readText()
            for (key in listOf("spotify_auth_client_id_missing", "settings_spotify_client_missing_build")) {
                val value = Regex("<string name=\"$key\">(.*?)</string>").find(text)!!.groupValues[1]
                assertTrue("$locale/$key", value.contains("README"))
            }
            assertFalse(locale, text.contains("settings_spotify_client_bundled"))
        }
    }
}
