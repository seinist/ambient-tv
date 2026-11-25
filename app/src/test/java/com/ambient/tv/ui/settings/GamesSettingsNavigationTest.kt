package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class GamesSettingsNavigationTest {
    @Test fun coreDetailsReturnToTheirConsole() {
        assertEquals(GamesSettingsPage.SYSTEM, gamesSettingsParent(GamesSettingsPage.CORE, fromSystem = true))
        assertEquals(GamesSettingsPage.SYSTEMS, gamesSettingsParent(GamesSettingsPage.SYSTEM))
    }

    @Test fun allEmulatorsReturnToSystemIndex() {
        assertEquals(GamesSettingsPage.SYSTEMS, gamesSettingsParent(GamesSettingsPage.CORE))
        assertEquals(GamesSettingsPage.SYSTEMS, gamesSettingsParent(GamesSettingsPage.ALL_CORES))
    }

    @Test fun downloadsAndAchievementsReturnToTheirCategory() {
        assertEquals(GamesSettingsPage.STORAGE, gamesSettingsParent(GamesSettingsPage.SHADERS))
        assertEquals(GamesSettingsPage.STORAGE, gamesSettingsParent(GamesSettingsPage.SYSTEM_FILES))
        assertEquals(GamesSettingsPage.STORAGE, gamesSettingsParent(GamesSettingsPage.CACHE))
        assertEquals(GamesSettingsPage.PLAY, gamesSettingsParent(GamesSettingsPage.ACHIEVEMENTS))
    }

    @Test fun searchAndDirectShortcutsReachTheExpectedSection() {
        assertEquals(GamesSettingsPage.SYSTEMS, gamesSettingsPageForSearch("systems"))
        assertEquals(GamesSettingsPage.METADATA, gamesSettingsPageForSearch("metadata"))
        assertEquals(GamesSettingsPage.SHADERS, gamesSettingsPageForSearch("Shader"))
        assertEquals(GamesSettingsPage.CACHE, gamesSettingsPageForSearch("Spiele-Cache"))
        assertEquals(GamesSettingsPage.ACHIEVEMENTS, gamesSettingsPageForSearch("RetroAchievements"))
        assertEquals("systems", settingsShortcutGamesTarget("games:systems"))
        assertEquals("home", settingsShortcutGamesTarget("games:home"))
        assertEquals("library", settingsShortcutGamesTarget("games:library"))
        assertEquals(GamesSettingsPage.LIBRARY, gamesSettingsPageForSearch("library"))
        assertEquals(GamesSettingsPage.LIBRARY, gamesSettingsPageForSearch("Spielebibliothek"))
        assertEquals(null, settingsShortcutGamesTarget("games:not-a-page"))
    }

    @Test fun categoriesReturnToGamesOverview() {
        for (page in listOf(GamesSettingsPage.LIBRARY, GamesSettingsPage.SYSTEMS, GamesSettingsPage.METADATA,
                GamesSettingsPage.CONTROLS, GamesSettingsPage.PLAY, GamesSettingsPage.STORAGE)) {
            assertEquals(GamesSettingsPage.HOME, gamesSettingsParent(page))
        }
    }
}
