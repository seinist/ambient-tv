package com.ambient.tv.ui.settings

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SettingsBackupManager
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class ViewTransitionSettingsTest {
    @Test fun oldAppearanceDraftsDefaultToOffAndKeepIndependentSelections() {
        val old = SettingsUiState(themeModeKey = "oled~chrome~ambient")
        assertEquals("off", old.viewTransitionStyleKey)
        assertEquals("ambient", old.accentMotionKey)
        val edited = old.withViewTransitionStyleKey("cinematic")
            .withAccentStyleKey("pearl").withAccentMotionKey("focus").withThemeModeKey("dim")
        assertEquals("cinematic", edited.viewTransitionStyleKey)
        assertEquals("pearl", edited.accentStyleKey)
        assertEquals("focus", edited.accentMotionKey)
        assertEquals("dim", edited.displayThemeModeKey)
        assertEquals("off", edited.withViewTransitionStyleKey("unknown").viewTransitionStyleKey)
    }

    @Test fun independentStoredAccentEditCannotDiscardDraftTransition() {
        val baseline = SettingsUiState()
        val draft = baseline.withViewTransitionStyleKey("slide")
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        val stored = baseline.withAccentStyleKey("chrome").withAccentMotionKey("focus")
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals("slide", merged.viewTransitionStyleKey)
        assertEquals("chrome", merged.accentStyleKey)
        assertEquals("focus", merged.accentMotionKey)
    }

    @Test fun preferenceNormalizesUnknownStylesAndBackupRoundTripsAllChoices() {
        val storage = memoryPreferences()
        val prefs = AppPreferences(storage)
        val manager = SettingsBackupManager(prefs, TokenStore(memoryPreferences(), prefs))
        assertEquals("off", prefs.viewTransitionStyleKey)
        for (style in listOf("off", "fade", "slide", "cinematic")) {
            prefs.viewTransitionStyleKey = style
            val exported = manager.exportJson()
            prefs.viewTransitionStyleKey = "off"
            manager.importJson(exported)
            assertEquals(style, AppPreferences(storage).viewTransitionStyleKey)
        }
        prefs.viewTransitionStyleKey = "slide"
        manager.importJson("""{"schemaVersion":81,"exportedAtEpochMs":0}""")
        assertEquals("slide", prefs.viewTransitionStyleKey)
        prefs.viewTransitionStyleKey = "invalid"
        assertEquals("off", prefs.viewTransitionStyleKey)
    }
}
