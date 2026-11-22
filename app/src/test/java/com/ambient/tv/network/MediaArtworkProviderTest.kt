package com.ambient.tv.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MediaArtworkProviderTest {
    private val proxy = listOf(
        MediaImageEndpoint("plex", "proxy.test", 443, true),
        MediaImageEndpoint("jellyfin", "https://proxy.test", 443, true, "/jellyfin"),
        MediaImageEndpoint("emby", "proxy.test", 443, true, "/emby"),
    )

    @Test fun sharedProxyUsesCompleteBasePathsAndTheMostSpecificEndpoint() {
        assertEquals("jellyfin", mediaImageProvider("https://proxy.test/jellyfin/Items/42/Images/Primary".toHttpUrl(), proxy))
        assertEquals("emby", mediaImageProvider("https://proxy.test/emby/Items/42/Images/Primary".toHttpUrl(), proxy))
        assertEquals("plex", mediaImageProvider("https://proxy.test/photo/:/transcode?url=42".toHttpUrl(), proxy))
        assertNull(mediaImageProvider("https://proxy.test/jellyfin-other/Items/42".toHttpUrl(), proxy.drop(1)))
        assertNull(mediaImageProvider("http://proxy.test:443/jellyfin/Items/42".toHttpUrl(), proxy))
        assertNull(mediaImageProvider("https://proxy.test:8096/jellyfin/Items/42".toHttpUrl(), proxy))
        assertNull(mediaImageProvider("https://other.test/jellyfin/Items/42".toHttpUrl(), proxy))
    }

    @Test fun ambiguousAccountsAtOneRootAreNotGuessed() {
        val root = proxy.map { it.copy(basePath = "") }
        assertNull(mediaImageProvider("https://proxy.test/Items/42/Images/Primary".toHttpUrl(), root))
        assertEquals("plex", mediaImageProvider("https://proxy.test/library/metadata/42/thumb".toHttpUrl(), root))
    }

    @Test fun basePathsUseTheSameEncodingAsProviderRequestBuilders() {
        val endpoints = listOf(MediaImageEndpoint("jellyfin", "proxy.test", 443, true, "/Meine Filme/öffentliche"))
        val request = "https://proxy.test".toHttpUrl().newBuilder().encodedPath("/Meine Filme/öffentliche/Items/42/Images/Primary").build()
        assertEquals("jellyfin", mediaImageProvider(request, endpoints))
        assertEquals("jellyfin", mediaImageProvider(request, endpoints.map { it.copy(basePath = "/Meine%20Filme/%C3%B6ffentliche") }))
        assertNull(mediaImageProvider(request.newBuilder().encodedPath("/Meine Filme/öffentliche2/Items/42").build(), endpoints))
    }

    @Test fun everyResizeVariantSharesTheResourceButOtherImagesAndTagsDoNot() {
        val plex = "https://proxy.test/photo/:/transcode?width=360&height=540&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F42%2Fthumb&X-Plex-Token=secret"
        assertEquals(mediaArtworkResourceUrl(plex), mediaArtworkResourceUrl(plex.replace("width=360", "width=960").replace("height=540", "height=720")))
        assertNotEquals(mediaArtworkResourceUrl(plex), mediaArtworkResourceUrl(plex.replace("42", "43")))
        val jellyfin = "https://proxy.test/jellyfin/Items/42/Images/Primary?tag=one&maxWidth=960&quality=85&ApiKey=secret"
        assertEquals(mediaArtworkResourceUrl(jellyfin), mediaArtworkResourceUrl(jellyfin.replace("maxWidth=960", "maxWidth=1280").replace("quality=85", "quality=95")))
        assertNotEquals(mediaArtworkResourceUrl(jellyfin), mediaArtworkResourceUrl(jellyfin.replace("tag=one", "tag=two")))
        assertFalse(requireNotNull(mediaArtworkResourceUrl(jellyfin)).contains("secret"))
    }

    @Test fun headerIdentityIsCanonicalAccountScopedAndContainsNoCredential() {
        val first = mediaArtworkHeadersKey(linkedMapOf("Authorization" to "Basic secret-a", "Referer" to "app"))
        assertEquals(first, mediaArtworkHeadersKey(linkedMapOf("referer" to "app", "authorization" to "Basic secret-a")))
        assertNotEquals(first, mediaArtworkHeadersKey(mapOf("Authorization" to "Basic secret-b", "Referer" to "app")))
        assertTrue(requireNotNull(first).matches(Regex("[0-9a-f]{64}")))
        assertNull(mediaArtworkHeadersKey(emptyMap()))
    }

    @Test fun sourceIdentityDoesNotObservePlaybackProgressOrCosmeticPreferences() {
        for (key in listOf("plex_token_one", "jellyfin_token_one", "emby_token_one", "server_profiles_v1", "jellyfin_base_path", "metadata_language"))
            assertTrue(key, mediaArtworkSourcePreference(key))
        for (key in listOf("playback_position", "subtitle_size", "image_cache_mb", "jellyfin_theme_music_enabled"))
            assertFalse(key, mediaArtworkSourcePreference(key))
    }
}
