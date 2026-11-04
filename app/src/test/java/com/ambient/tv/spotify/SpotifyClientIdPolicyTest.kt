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
        // Only the opt-in private key file (build.sh PRIVATE_KEYS) may provide one; public and
        // test builds have none, and Gradle never names a fixed value.
        val gradle = File("build.gradle.kts").readText()
        assertFalse(gradle.contains("\"SPOTIFY_CLIENT_ID\" to"))
        assertTrue(gradle.contains("privateKeys[name].orEmpty()"))
        assertEquals("", com.ambient.tv.BuildConfig.BUILTIN_SPOTIFY_CLIENT_ID)
        assertNull(com.ambient.tv.data.local.BuiltInKeys.spotifyClientId)
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
