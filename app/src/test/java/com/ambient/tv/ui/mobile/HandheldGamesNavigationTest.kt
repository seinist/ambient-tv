package com.ambient.tv.ui.mobile

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.SidebarUiState
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HandheldGamesNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun assignedSystemsAreReachableAlphabeticallyFromPhoneSources() {
        var group: SourceGroup? = null
        rule.setContent {
            val current = handheldGameSourceGroup(SidebarUiState(
                gamesAvailable = true,
                gameSystemIds = listOf("snes", "psx", "nes", "snes", "unknown"),
                offlineSystems = setOf("files:nas"),
            ))
            SideEffect { group = current }
        }
        rule.runOnIdle {
            val games = requireNotNull(group)
            assertEquals("games", games.key)
            assertEquals(listOf("games:all", "games:favorites", "games:recent", "games:nes",
                "games:psx", "games:snes", "settings"), games.entries.map { it.key })
            assertEquals(Destinations.GAME_LIBRARY, games.entries.first().route)
            val snes = games.entries.first { it.key == "games:snes" }
            assertEquals(Destinations.gameLibrarySection("snes"), snes.route)
            assertTrue(handheldSourceSelected(snes.route, snes.route))
            assertTrue(snes.icon != null)
            assertEquals("games", games.entries.last().settingsCategory)
        }
    }

    @Test fun gamesStayHiddenWithoutConfiguredFoldersAndRespectSettingsShortcut() {
        var state by mutableStateOf(SidebarUiState(gameSystemIds = listOf("snes")))
        var group: SourceGroup? = null
        rule.setContent {
            val current = handheldGameSourceGroup(state)
            SideEffect { group = current }
        }
        rule.runOnIdle {
            assertNull(group)
            state = state.copy(gamesAvailable = true, settingsShortcutHidden = setOf("games"))
        }
        rule.runOnIdle {
            assertTrue(requireNotNull(group).entries.any { it.key == "games:snes" })
            assertFalse(requireNotNull(group).entries.any { it.settingsCategory != null })
        }
    }
}
