package com.ambient.tv.ui.youtube

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTrackPresentationTest {
    @Test fun `localizes YouTube locale codes and keeps track qualifiers`() {
        assertEquals("Deutsch", youtubeTrackLabel("de-DE", Locale.GERMAN))
        assertEquals("Englisch (Dubbed-auto)", youtubeTrackLabel("en‐US (dubbed-auto)", Locale.GERMAN))
        assertEquals("Deutsch (Original) · opus · 2ch", youtubeTrackLabel("de-DE (original) · opus · 2ch", Locale.GERMAN))
    }

    @Test fun `recognizes only explicitly marked original audio`() {
        assertTrue(isYouTubeOriginalAudioTrack("ja (original)", null))
        assertTrue(isYouTubeOriginalAudioTrack("ja", "Japanese original"))
        assertFalse(isYouTubeOriginalAudioTrack("en-US (dubbed-auto)", "English"))
        assertFalse(isYouTubeOriginalAudioTrack("de-DE", null))
    }
}
