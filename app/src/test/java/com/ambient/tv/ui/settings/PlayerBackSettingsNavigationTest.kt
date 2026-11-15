package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerBackSettingsNavigationTest {
    @Test fun backSearchLandsOnTheSwitchInsteadOfTheRenderer() {
        listOf(
            "Zurück im Player",
            "zurueck",
            "Player Back",
            "minimieren",
            "minimize",
            "im Hintergrund weiterspielen",
            "background playback",
        ).forEach { query ->
            assertEquals(query, "playback:back", settingsSearchTargetKey(SettingsCategory.PLAYBACK, query))
        }
    }

    @Test fun backShortcutUsesThePlaybackPageAndItsStableAnchor() {
        assertEquals(SettingsCategory.PLAYBACK, settingsShortcutCategory("playback:back"))
        assertEquals("playback:back", settingsShortcutItemKey("playback:back"))
        assertNull(settingsShortcutItemKey("playback:unknown"))
    }

    @Test fun rendererAndPerformanceSearchStillReachTheirOwnSettings() {
        assertEquals("playback:renderer", settingsSearchTargetKey(SettingsCategory.PLAYBACK, "MPV backend"))
        assertEquals("playback:test-duration", settingsSearchTargetKey(SettingsCategory.PLAYBACK, "Prüfdauer Bildaussetzer"))
    }
}
