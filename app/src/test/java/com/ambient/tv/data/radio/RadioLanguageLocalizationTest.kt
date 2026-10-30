package com.ambient.tv.data.radio

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioLanguageLocalizationTest {
    @Test fun isoCodesUseTheRequestedDisplayLocale() {
        assertEquals("Deutsch", radioLanguageDisplayName("deu", "German", Locale.GERMAN))
        assertEquals("Englisch", radioLanguageDisplayName("en", "English", Locale.GERMAN))
        assertEquals("German", radioLanguageDisplayName("de", "German", Locale.ENGLISH))
    }

    @Test fun providerEnglishNamesStillLocalizeWithoutIsoMetadata() {
        assertEquals("Französisch", radioLanguageDisplayName(null, "French", Locale.GERMAN))
        assertEquals("Spanisch", radioLanguageDisplayName(null, "Spanish", Locale.GERMAN))
    }

    @Test fun stationLanguagesPreferCodesAndStayDistinct() {
        val station = RadioStation(
            id = "one",
            name = "One",
            streamUrl = "https://example.test/one",
            homepage = null,
            artworkUrl = null,
            country = "Germany",
            countryCode = "DE",
            state = null,
            languages = listOf("German", "English", "German"),
            tags = emptyList(),
            codec = "MP3",
            bitrateKbps = 128,
            isHls = false,
            votes = 0,
            clickCount = 0,
            languageCodes = listOf("de", "en", "de"),
        )

        assertEquals(listOf("Deutsch", "Englisch"), station.localizedLanguageNames(Locale.GERMAN))
    }
}
