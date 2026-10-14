package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLanguagePolicyTest {
    private fun subtitle(
        id: Long,
        languageCode: String,
        title: String,
        forced: Boolean,
    ) = MediaTrack(
        id = id,
        type = MediaTrackType.SUBTITLE,
        codec = "srt",
        language = null,
        languageCode = languageCode,
        title = title,
        channels = null,
        selected = false,
        forced = forced,
        externalUrl = null,
    )

    @Test
    fun `recognizes localized language and forced labels`() {
        assertTrue(PlaybackLanguagePolicy.labelMatchesLanguage("Deutsch · EAC3 5.1", "de-DE"))
        assertTrue(PlaybackLanguagePolicy.labelMatchesLanguage("English", "eng"))
        assertTrue(PlaybackLanguagePolicy.looksForced("Deutsch Forced"))
        assertTrue(PlaybackLanguagePolicy.looksForced("Deutsch erzwungen"))
        assertEquals("en", PlaybackLanguagePolicy.inferLanguageFromLabel("English · EAC3 5.1"))
        assertEquals("de", PlaybackLanguagePolicy.inferLanguageFromLabel("Deutsch"))
    }

    @Test
    fun `forced VLC mapping prefers provider title`() {
        val source = listOf(
            subtitle(10, "de", "Deutsch", false),
            subtitle(11, "de", "Deutsch Forced", true),
        )
        val vlc = listOf(1 to "Deutsch", 2 to "Deutsch Forced")

        assertEquals(2, PlaybackLanguagePolicy.chooseVlcTrackId(vlc, source, "de", forcedOnly = true))
    }

    @Test
    fun `forced VLC mapping preserves same-language provider ordering when disposition is hidden`() {
        val source = listOf(
            subtitle(10, "de", "Standard", false),
            subtitle(11, "de", "Nur erzwungen", true),
        )
        val vlc = listOf(1 to "Deutsch", 2 to "Deutsch")

        assertEquals(2, PlaybackLanguagePolicy.chooseVlcTrackId(vlc, source, "de", forcedOnly = true))
    }
}
