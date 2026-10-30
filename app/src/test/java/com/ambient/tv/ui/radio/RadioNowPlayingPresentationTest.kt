package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioNowPlayingPresentationTest {
    @Test fun stationWithoutSongShowsOnlyTheStationAndProviderContext() {
        val result = radioHeaderPresentation("  Radio Paradise  ", "", "Radio Browser")
        assertEquals("Radio Paradise", result.title)
        assertEquals("Radio Browser", result.contextLine)
        assertNull(result.subtitle)
    }

    @Test fun stationNameIsNotMisidentifiedAsSong() {
        val result = radioHeaderPresentation("Radio Paradise", "radio paradise", "Radio Browser")
        assertEquals("Radio Paradise", result.title)
        assertNull(result.subtitle)
    }

    @Test fun currentSongUsesLiveTvHierarchy() {
        val result = radioHeaderPresentation("  Radio Paradise ", "  Pink Floyd - Time  ", "Radio Browser")
        assertEquals("Radio Paradise", result.contextLine)
        assertEquals("Time", result.title)
        assertEquals("Pink Floyd", result.subtitle)
    }

    @Test fun unicodeDashAndUnstructuredTitlesRemainReadable() {
        val dash = radioHeaderPresentation("Station", "Björk – Joga", "Radio Browser")
        assertEquals("Joga", dash.title)
        assertEquals("Björk", dash.subtitle)
        val raw = radioHeaderPresentation("Station", "Live at the BBC", "Radio Browser")
        assertEquals("Live at the BBC", raw.title)
        assertNull(raw.subtitle)
    }

    @Test fun blankStationFallsBackToServiceNameWithoutTechnicalMetadata() {
        val result = radioHeaderPresentation(" ", null, "Radio Browser")
        assertEquals("Radio Browser", result.title)
        assertEquals("Radio Browser", result.contextLine)
        assertNull(result.subtitle)
    }
}
