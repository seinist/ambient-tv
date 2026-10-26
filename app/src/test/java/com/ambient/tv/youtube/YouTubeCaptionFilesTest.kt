package com.ambient.tv.youtube

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** G4 of the 26.10. audit: captions appear as they finish, old copies are removed. */
class YouTubeCaptionFilesTest {
    private val urls = listOf("de", "en", "de-auto")

    @Test fun readyTracksAreShownInLoadOrder() {
        assertEquals(emptyMap<String, String>(), youtubeCaptionsReadyInOrder(urls, listOf(null, "f:en", null)))
        assertEquals(mapOf("de" to "f:de", "en" to "f:en"), youtubeCaptionsReadyInOrder(urls, listOf("f:de", "f:en", null)))
        assertEquals(mapOf("en" to "f:en", "de-auto" to "f:auto"), youtubeCaptionsReadyInOrder(urls, listOf("", "f:en", "f:auto")))
    }

    @Test fun onlyCopiesOlderThanSevenDaysAreDeleted() {
        val dir = Files.createTempDirectory("captions").toFile()
        try {
            val now = 100L * 24 * 60 * 60 * 1000
            val old = File(dir, "old.vtt").apply { writeText("x"); setLastModified(now - YOUTUBE_CAPTION_MAX_AGE_MS - 1_000) }
            val fresh = File(dir, "fresh.vtt").apply { writeText("x"); setLastModified(now - 60_000) }
            assertEquals(listOf(old), youtubeCaptionFilesToDelete(listOf(old, fresh), now))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun downloadsAreBounded() = assertEquals(3, YOUTUBE_CAPTION_DOWNLOADS)
}
