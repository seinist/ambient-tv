package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSelectionPreferencesTest {
    @Test fun detailsIsTheSafeDefault() {
        assertEquals(MediaSelectionPreferences.DETAILS, MediaSelectionPreferences.normalize(null))
        assertEquals(MediaSelectionPreferences.DETAILS, MediaSelectionPreferences.normalize("unknown"))
    }

    @Test fun supportedModesArePreserved() {
        assertEquals(MediaSelectionPreferences.DETAILS, MediaSelectionPreferences.normalize("details"))
        assertEquals(MediaSelectionPreferences.PLAY, MediaSelectionPreferences.normalize("play"))
        assertEquals(MediaSelectionPreferences.ASK, MediaSelectionPreferences.normalize("ask"))
    }

    @Test fun youtubeDefaultIsPlayImmediately() {
        assertEquals(MediaSelectionPreferences.PLAY, MediaSelectionPreferences.normalize(null, MediaSelectionPreferences.PLAY))
        assertEquals(MediaSelectionPreferences.PLAY, MediaSelectionPreferences.normalize("unknown", MediaSelectionPreferences.PLAY))
        assertEquals(MediaSelectionPreferences.DETAILS, MediaSelectionPreferences.normalize("details", MediaSelectionPreferences.PLAY))
    }
}
