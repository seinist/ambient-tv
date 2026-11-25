package com.ambient.tv.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.ambient.tv.R
import com.ambient.tv.data.dlna.DlnaServer
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.SystemNameOverrides
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.livetv.AvailableLiveTvSource
import com.ambient.tv.livetv.LiveTvCapabilities
import com.ambient.tv.livetv.LiveTvSource
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the shared menu inventory and actual editor autosave through Compose text input. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class MenuOrderUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun clearNamesBefore() { SystemNameOverrides.names = emptyMap() }
    @After fun clearNamesAfter() { SystemNameOverrides.names = emptyMap() }

    private fun allSystems() = SidebarUiState(
        trailersConfigured = true,
        filesEnabled = true,
        gamesAvailable = true,
        dlnaServers = listOf(DlnaServer(
            id = "nas", udn = "uuid:nas", name = "NAS",
            descriptionUrl = "http://192.0.2.1/description.xml",
            contentDirectoryUrl = "http://192.0.2.1/content",
        )),
        plexEnabled = true,
        jellyfinEnabled = true,
        embyEnabled = true,
        youtubeEnabled = true,
        spotifyEnabled = true,
        spotifyAuthorized = true,
        telegramEnabled = true,
        telegramAuthorized = true,
        radioEnabled = true,
        rommEnabled = true,
        weatherEnabled = true,
        tvheadendEnabled = true,
        liveTvSources = listOf(AvailableLiveTvSource(
            LiveTvSource("iptv:one", "iptv", "Playlist"),
            LiveTvCapabilities(available = true, guide = true),
        )),
        systemOrder = listOf("weather", "radio", "tvheadend", "iptv"),
    )

    @Test fun everyConfiguredSystemAndWeatherUseTheSharedOrderAndMenuNames() {
        var items = emptyList<MenuOrderItem>()
        rule.setContent {
            val current = menuOrderItems(allSystems())
            SideEffect { items = current }
        }
        rule.runOnIdle {
            assertEquals(MediaSystem.entries.filterNot { it == MediaSystem.MOONLIGHT }.map { it.key }.toSet() +
                setOf("weather", "games"), items.map { it.id }.toSet())
            assertEquals(MediaSystem.entries.size + 1, items.size)
            assertEquals(listOf("weather", "radio", "tvheadend", "iptv"), items.take(4).map { it.id })
            val byId = items.associateBy { it.id }
            assertEquals("TVHeadend", byId.getValue("tvheadend").label)
            assertEquals("Radio Browser", byId.getValue("radio").label)
            assertEquals("SmartTube", byId.getValue("youtube").label)
            assertEquals("IPTV", byId.getValue("iptv").label)
            assertEquals(rule.activity.getString(R.string.nav_files), byId.getValue("files").label)
            assertEquals(rule.activity.getString(R.string.weather_title), byId.getValue("weather").label)
            assertNull(byId.getValue("weather").system)
            assertEquals(rule.activity.getString(R.string.game_library_title), byId.getValue("games").label)
        }
    }

    @Test fun gamesAppearAfterFolderSetupAndKeepTheirSavedOrderAndName() {
        val storage = memoryPreferences()
        val prefs = AppPreferences(storage)
        prefs.systemNames = mapOf("games" to "Retro")
        prefs.sidebarSystemOrder = listOf("games", "files")
        val restored = AppPreferences(storage)
        var state by mutableStateOf(SidebarUiState(filesEnabled = true,
            systemOrder = restored.sidebarSystemOrder))
        var items = emptyList<MenuOrderItem>()
        rule.setContent {
            val current = menuOrderItems(state)
            SideEffect { items = current }
        }
        rule.runOnIdle {
            assertFalse(items.any { it.id == "games" })
            state = state.copy(gamesAvailable = true)
        }
        rule.runOnIdle {
            assertEquals(listOf("games", "files"), items.map { it.id })
            assertEquals("Retro", items.first().label)
            state = state.copy(gamesAvailable = false)
        }
        rule.runOnIdle { assertEquals(listOf("files"), items.map { it.id }) }
    }

    @Test fun customNamesUpdateEveryEntryWithoutChangingDefaultsOrShowingDisabledWeather() {
        var state by mutableStateOf(allSystems())
        var items = emptyList<MenuOrderItem>()
        rule.setContent {
            val current = menuOrderItems(state)
            SideEffect { items = current }
        }
        val defaults = rule.runOnIdle { items.associate { it.id to it.defaultLabel } }
        val custom = defaults.keys.associateWith { "Own $it" }
        rule.runOnIdle { SystemNameOverrides.names = custom }
        rule.runOnIdle {
            assertEquals(custom, items.associate { it.id to it.label })
            assertEquals(defaults, items.associate { it.id to it.defaultLabel })
            assertEquals("TVHeadend", items.first { it.id == "tvheadend" }.defaultLabel)
            assertEquals(rule.activity.getString(R.string.weather_title), items.first { it.id == "weather" }.defaultLabel)
            state = state.copy(weatherEnabled = false)
        }
        rule.runOnIdle {
            assertFalse(items.any { it.id == "weather" })
            assertEquals(custom - "weather", items.associate { it.id to it.label })
            assertEquals("Own weather", SystemNameOverrides.names["weather"])
        }
    }

    @Test fun restoringTheOriginalDefaultAfterAutosaveRemovesTheIntermediateName() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.systemNames = mapOf("plex" to "Cinema")
        var dismissed = false
        openEditor(SidebarUiState(radioEnabled = true), prefs) { dismissed = true }
        replaceName("My stations")
        assertEquals("My stations", prefs.systemNames["radio"])
        replaceName("Radio Browser")
        assertEquals(mapOf("plex" to "Cinema"), prefs.systemNames)
        closeEditor()
        assertTrue(dismissed)
        assertEquals(mapOf("plex" to "Cinema"), prefs.systemNames)
    }

    @Test fun weatherCustomNameCanBeRestoredAndThenClearedWithoutBeingSavedAgainOnClose() {
        val prefs = AppPreferences(memoryPreferences())
        prefs.systemNames = mapOf("weather" to "Forecast", "radio" to "Stations")
        openEditor(SidebarUiState(weatherEnabled = true), prefs)
        replaceName("At home")
        assertEquals("At home", prefs.systemNames["weather"])
        replaceName("Forecast")
        assertEquals("Forecast", prefs.systemNames["weather"])
        replaceName("")
        assertEquals(mapOf("radio" to "Stations"), prefs.systemNames)
        closeEditor()
        assertEquals(mapOf("radio" to "Stations"), prefs.systemNames)
    }

    private fun openEditor(state: SidebarUiState, prefs: AppPreferences, onDismiss: () -> Unit = {}) {
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) MenuOrderDialog(
                systems = menuOrderItems(state),
                onApplyOrder = { prefs.sidebarSystemOrder = it },
                onRename = { names, edited -> prefs.systemNames = prefs.systemNames.filterKeys { it !in edited } + names },
                onDismiss = { open = false; onDismiss() },
            )
        }
        rule.waitForIdle()
        val label = hasText(rule.activity.getString(R.string.sidebar_customize_name))
        rule.onNode((label or hasAnyDescendant(label)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertExists()
    }

    private fun replaceName(value: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(value)
        rule.waitForIdle()
    }

    private fun closeEditor() {
        val done = rule.activity.getString(R.string.settings_home_genre_done)
        rule.onNode(hasText(done) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitForIdle()
    }
}
