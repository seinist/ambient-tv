package com.ambient.tv.ui.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackDisplayTest {
    @Test fun audioLabelsExpandTwoAndThreeLetterLanguages() {
        assertEquals("Deutsch", expandAudioLanguageCodes("de", Locale.GERMAN))
        assertEquals("Deutsch · AC3 5.1", expandAudioLanguageCodes("DEU · AC3 5.1", Locale.GERMAN))
        assertEquals("Deutsch (Kommentar)", expandAudioLanguageCodes("ger (Kommentar)", Locale.GERMAN))
        assertEquals("German · Dolby", expandAudioLanguageCodes("de-DE · Dolby", Locale.ENGLISH))
    }

    @Test fun technicalLabelsAndNamesArePreserved() {
        assertEquals("HDMI / ARC · DTS-HD", expandAudioLanguageCodes("HDMI / ARC · DTS-HD", Locale.GERMAN))
        assertEquals("Deutsch · Dolby Digital", expandAudioLanguageCodes("Deutsch · Dolby Digital", Locale.GERMAN))
    }
}
