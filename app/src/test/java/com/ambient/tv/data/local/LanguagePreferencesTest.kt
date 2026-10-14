package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePreferencesTest {
    @Test fun playbackAutoFollowsUiAndExplicitPreferenceWins() {
        assertEquals("de", LanguagePreferences.resolvePlayback("auto", "de", "en-US"))
        assertEquals("de", LanguagePreferences.resolvePlayback("ger", "en", "en-US"))
        assertEquals("en", LanguagePreferences.resolvePlayback("en", "de", "de-DE"))
        assertEquals("de", LanguagePreferences.resolvePlayback("auto", "auto", "de-DE"))
        assertEquals(listOf("de", "deu", "ger"), LanguagePreferences.playbackLanguageTags("de-DE"))
    }

    @Test
    fun `normalizes common ISO aliases`() {
        assertEquals("de", LanguagePreferences.baseLanguage("de-DE"))
        assertEquals("de", LanguagePreferences.baseLanguage("deu"))
        assertEquals("de", LanguagePreferences.baseLanguage("ger"))
        assertEquals("en", LanguagePreferences.baseLanguage("en-US"))
        assertEquals("en", LanguagePreferences.baseLanguage("eng"))
    }

    @Test
    fun `matches language aliases without coupling region`() {
        assertTrue(LanguagePreferences.matches("deu", "de-DE"))
        assertTrue(LanguagePreferences.matches("ger", "de"))
        assertTrue(LanguagePreferences.matches("eng", "en-GB"))
        assertFalse(LanguagePreferences.matches("de", "en-US"))
    }

    @Test
    fun `blank preference normalizes to auto`() {
        assertEquals(LanguagePreferences.AUTO, LanguagePreferences.normalizeKey(null))
        assertEquals(LanguagePreferences.AUTO, LanguagePreferences.normalizeKey("  "))
    }
}
