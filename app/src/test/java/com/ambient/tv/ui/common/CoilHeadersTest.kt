package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class CoilHeadersTest {
    private val credentials = mapOf("Authorization" to "Basic secret", "Referer" to "https://tv.example")

    @Test fun tmdbArtworkNeverReceivesMediaServerCredentials() {
        assertTrue(artworkHeadersForModel("https://image.tmdb.org/t/p/w780/background.jpg", credentials).isEmpty())
        assertTrue(artworkHeadersForModel("https://IMAGE.TMDB.ORG/t/p/w780/background.jpg", credentials).isEmpty())
    }

    @Test fun protectedLiveTvFallbackStillReceivesItsHeaders() {
        assertEquals(credentials, artworkHeadersForModel("https://tv.example/imagecache/123", credentials))
        assertEquals(credentials, artworkHeadersForModel(null, credentials))
    }

    @Test fun similarlyNamedHostIsNotMistakenForTmdb() {
        assertEquals(credentials, artworkHeadersForModel("https://image.tmdb.org.attacker.example/image", credentials))
    }
}
