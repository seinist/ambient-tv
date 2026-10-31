package com.ambient.tv.network

import com.ambient.tv.data.browse.displayArtworkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaArtworkDiskCacheInterceptorTest {
    @Test fun plexTokenFreeSnapshotReusesOriginalArtworkDiskKey() {
        val original = "http://192.168.1.5:32400/photo/:/transcode?width=360&height=540&url=%2Flibrary%2Fmetadata%2F42%2Fthumb&X-Plex-Token=secret"
        val restored = requireNotNull(displayArtworkUrl(original))
        val key = requireNotNull(normalizedMediaArtworkDiskKey(original, "plex:scopeA"))
        assertEquals(key, normalizedMediaArtworkDiskKey(restored, "plex:scopeA"))
        assertFalse(key.contains("secret"))
        assertNotEquals(key, normalizedMediaArtworkDiskKey(restored, "plex:scopeB"))
    }

    @Test fun jellyfinAndEmbyQueryTokensDoNotChangeDiskKeys() {
        for (token in listOf("ApiKey", "api_key")) {
            val original = "https://media.example:8096/Items/42/Images/Primary?tag=revision1&$token=sensitive"
            val sanitized = requireNotNull(displayArtworkUrl(original))
            assertEquals(normalizedMediaArtworkDiskKey(original, "account"),
                normalizedMediaArtworkDiskKey(sanitized, "account"))
            assertFalse(requireNotNull(normalizedMediaArtworkDiskKey(original, "account")).contains("sensitive"))
        }
    }

    @Test fun differentArtworkRevisionsRemainDistinct() {
        val before = "https://media.example/Items/42/Images/Primary?tag=revision1"
        val after = "https://media.example/Items/42/Images/Primary?tag=revision2"
        assertNotEquals(normalizedMediaArtworkDiskKey(before, "account"),
            normalizedMediaArtworkDiskKey(after, "account"))
    }
}
