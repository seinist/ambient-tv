package com.ambient.tv.data.local

import com.ambient.tv.ui.player.AudioTrackMetadata
import com.ambient.tv.ui.player.choosePreferredAudioTrack
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageChoicesTest {
    @Test fun `full ISO 639-1 list in the display locale, sorted`() {
        val de = LanguagePreferences.playbackLanguageChoices(Locale.GERMAN)
        assertTrue(de.size > 150)
        assertTrue(de.contains("fr" to "Französisch"))
        assertTrue(LanguagePreferences.playbackLanguageChoices(Locale.ENGLISH).contains("fr" to "French"))
        val names = de.map { it.second }
        assertEquals(names.sortedWith(java.text.Collator.getInstance(Locale.GERMAN)), names)
    }

    @Test fun `ISO 639-2 T and B codes match`() {
        for ((track, pref) in listOf("deu" to "de", "ger" to "de", "fra" to "fr", "fre" to "fr", "nld" to "nl", "dut" to "nl",
            "ces" to "cs", "cze" to "cs", "zho" to "zh", "chi" to "zh", "sqi" to "sq", "alb" to "sq", "fas" to "fa", "per" to "fa",
            "isl" to "is", "ice" to "is", "cym" to "cy", "wel" to "cy", "jpn" to "ja", "heb" to "he")) {
            assertTrue("$track/$pref", LanguagePreferences.matches(track, pref))
        }
    }

    @Test fun `original language keeps the original track and does not leak into subtitles`() {
        assertEquals(LanguagePreferences.ORIGINAL, LanguagePreferences.resolvePlayback("original", "de", "en"))
        assertFalse(LanguagePreferences.matches("de", LanguagePreferences.ORIGINAL))
        assertEquals("de", LanguagePreferences.resolveViewerLanguage("original", "de", "en"))
        val tracks = listOf(AudioTrackMetadata("de", "Deutsch"), AudioTrackMetadata("en", "English (Original)"))
        assertEquals(tracks[1], choosePreferredAudioTrack(tracks, "original", AudioTrackPreference()) { it })
        assertNull(choosePreferredAudioTrack(tracks.take(1), "original", AudioTrackPreference()) { it })
    }
}
