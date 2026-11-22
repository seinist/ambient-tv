package com.ambient.tv.games.metadata

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GameMetadataUrlPolicyTest {
    @Test fun everyStoredUrlIsSanitizedIncludingArtworkAttributionMaps() {
        val unsafe = "https://api.screenscraper.fr/api2/mediaJeu.php?jeuid=42&devid=app-secret&DEV%50ASSWORD=password-secret&ssid=user-secret&sspassword=account-secret#token-secret"
        val metadata = GameMetadata(coverUrl = unsafe, backdropUrl = unsafe, titleScreenUrl = unsafe, sourceUrl = unsafe,
            artworkSources = mapOf("cover" to unsafe, "backdrop" to unsafe, "title" to unsafe, "unknown" to unsafe)).bounded()
        val safe = "https://api.screenscraper.fr/api2/mediaJeu.php?jeuid=42"
        assertEquals(safe, metadata.coverUrl)
        assertEquals(safe, metadata.backdropUrl)
        assertEquals(safe, metadata.titleScreenUrl)
        assertEquals(safe, metadata.sourceUrl)
        assertEquals(mapOf("cover" to safe, "backdrop" to safe, "title" to safe), metadata.artworkSources)
        assertFalse(Json.encodeToString(metadata).contains("secret"))
        assertEquals(metadata, metadata.bounded())
    }

    @Test fun encodedCaseVariantsAndAlternateQuerySeparatorsCannotHideSecrets() {
        val input = "https://example.org/image?id=7&API%5fKEY=secret;%2573sPassword=secret&x-api-key=secret&access%5Ftoken=secret&filename=public.png"
        assertEquals("https://example.org/image?id=7&filename=public.png", sanitizedGameMetadataUrl(input))
        assertEquals("https://example.org/image", sanitizedGameMetadataUrl("https://example.org/image?password=secret#secret"))
    }

    @Test fun userInfoAndNonWebSchemesAreRejectedAtTheSharedReadWriteBoundary() {
        for (value in listOf("https://user:secret@example.org/img", "https://user%3Asecret@example.org/img",
            "file:///sdcard/private.jpg", "content://private/image", "javascript:alert(1)", "//example.org/img",
            "https://example.org:0/img", "https://example.org:99999/img", "https://example.org/img\n?secret")) {
            assertNull(value, sanitizedGameMetadataUrl(value))
            assertNull(GameMetadata(coverUrl = value, sourceUrl = value).bounded().coverUrl)
        }
    }

    @Test fun publicAttributionUrlsRemainIndependentOfArtworkDownloadHostAllowlists() {
        val source = "http://museum.example/game?id=7&language=de"
        assertEquals(source, GameMetadata(sourceUrl = source).bounded().sourceUrl)
        assertNull(sanitizedGameMetadataUrl("https://example.org/" + "x".repeat(4096)))
        assertNull(sanitizedGameMetadataUrl("https://example.org/image?bad%=secret"))
    }
}
