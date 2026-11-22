package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class ArtworkFocusKeyTest {
    @Test fun keyIsIndependentOfHeaderOrderAndCaseButIsolatesAccountsAndVersions() {
        val url = "https://media.example/art/one?token=secret"
        val key = artworkFocusKey(url, linkedMapOf("Authorization" to "account-a", "Referer" to "app"), "version-1")
        assertEquals(key, artworkFocusKey(url, linkedMapOf("referer" to "app", "authorization" to "account-a"), "version-1"))
        assertNotEquals(key, artworkFocusKey(url, mapOf("Authorization" to "account-b", "Referer" to "app"), "version-1"))
        assertNotEquals(key, artworkFocusKey(url, mapOf("Authorization" to "account-a", "Referer" to "app"), "version-2"))
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("secret"))
        assertFalse(key.contains("account-a"))
    }

    @Test fun fieldBoundariesCannotCollide() {
        assertNotEquals(artworkFocusKey("ab", mapOf("c" to "d")), artworkFocusKey("a", mapOf("bc" to "d")))
        assertNotEquals(artworkFocusKey("image?v=1", emptyMap()), artworkFocusKey("image?v=2", emptyMap()))
    }

    @Test fun resolvedImageReusesAnalysisAcrossSnapshotAndLiveUrlVariants() {
        val snapshot = "https://media.example/art/one"
        val live = "$snapshot?token=current-secret"
        val resolved = "media-artwork-v1:account-a:$snapshot:refresh:revision-2"
        assertEquals(artworkFocusKey(snapshot, emptyMap(), resolved), artworkFocusKey(live, emptyMap(), resolved))
        assertNotEquals(artworkFocusKey(snapshot, emptyMap(), resolved),
            artworkFocusKey(live, emptyMap(), resolved.replace("account-a", "account-b")))
        assertNotEquals(artworkFocusKey(snapshot, emptyMap()), artworkFocusKey(live, emptyMap()))
    }
}
