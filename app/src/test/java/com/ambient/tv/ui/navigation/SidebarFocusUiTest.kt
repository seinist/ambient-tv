package com.ambient.tv.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Compose focus behaviour of the TV sidebar: entering, page switches, Back per level, Right,
 * the editor and held Left. Three systems (Trailer, TVHeadend, Files) give a main page plus pages.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SidebarFocusUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var state = SidebarUiState(
        trailersEnabled = true,
        trailersConfigured = true,
        tvheadendEnabled = true,
        tvheadendSubmenuKeys = listOf("live", "guide", "recordings"),
        filesEnabled = true,
    )
    private val actions = object : SidebarActions {
        override fun prefetch(route: String) = Unit
        override fun refresh(delayMs: Long) = Unit
        override fun applySystemOrder(order: List<String>) = Unit
        override fun renameSystems(names: Map<String, String>, edited: Set<String>) = Unit
        override fun toggleDivider(id: String) = Unit
        override fun applyEntryOrder(systemId: String, order: List<String>) { entryOrders += systemId to order }
        override fun toggleEntryDivider(systemId: String, id: String) = Unit
    }
    private val entryOrders = mutableListOf<Pair<String, List<String>>>()
    private var entryArmed = true
    private val ownership = mutableListOf<Boolean>()

    private fun setUp(startRoute: String) {
        rule.setContent {
            var route by remember { mutableStateOf(startRoute) }
            val content = remember { FocusRequester() }
            Row(Modifier.fillMaxSize()) {
                SidebarContent(
                    sidebarState = state,
                    viewModel = actions,
                    currentRoute = route,
                    currentSectionKey = null,
                    onNavigate = { route = it },
                    requestContentFocus = { runCatching { content.requestFocus() }.isSuccess },
                    isEntryRequested = { entryArmed },
                    onFocusOwnershipChanged = { ownership += it },
                )
                Box(Modifier.padding(start = 40.dp).size(200.dp).focusRequester(content).testTag("content").focusable())
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { content.requestFocus() }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("content").assertIsFocused()
    }

    private fun key(k: Key) {
        rule.onRoot().performKeyInput { pressKey(k) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focused(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()

    @Test fun inlineSingleSystemKeepsBothProviderConfigurationAndGlobalSettings() {
        state = SidebarUiState(trailersEnabled = true, trailersConfigured = true)
        setUp(Destinations.TRAILERS)
        key(Key.DirectionLeft)
        rule.onNodeWithTag("sidebar:system-settings:trailers", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("sidebar:settings", useUnmergedTree = true).assertExists()
    }

    @Test fun leftFromContentOpensOnTheActiveEntryOfTheActiveSystem() {
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        focused("sidebar:tvheadend:guide")
    }

    @Test fun leftWithoutAFreshPressDoesNotOpenTheBar() {
        setUp(Destinations.HOME)
        entryArmed = false
        key(Key.DirectionLeft)
        rule.onNodeWithTag("content").assertIsFocused()
    }

    @Test fun backOnASystemPageGoesToItsMainRowThenBackToContent() {
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        back()
        focused("sidebar:system:tvheadend")
        back()
        rule.onNodeWithTag("content").assertIsFocused()
    }

    @Test fun leftOnASystemPageGoesToTheMainPage() {
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        key(Key.DirectionLeft)
        focused("sidebar:system:tvheadend")
    }

    @Test fun rightClosesAndReturnsToContent() {
        setUp(Destinations.HOME)
        key(Key.DirectionLeft)
        focused("sidebar:home")
        key(Key.DirectionRight)
        rule.onNodeWithTag("content").assertIsFocused()
    }

    @Test fun openingASystemPageNeverDropsFocusOutOfTheBar() {
        setUp(Destinations.HOME)
        key(Key.DirectionLeft)
        focused("sidebar:home")
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        focused("sidebar:system:trailers")
        ownership.clear()
        key(Key.Enter)
        focused("sidebar:trailers:cinema")
        assertFalse("focus left the bar during the page switch: $ownership", false in ownership)
    }

    @Test fun upOnTheFirstRowWrapsToSettingsAndDownReturnsToFirst() {
        setUp(Destinations.HOME)
        key(Key.DirectionLeft)
        key(Key.DirectionUp)
        focused("sidebar:search")
        key(Key.DirectionUp)
        focused("sidebar:settings")
        key(Key.DirectionDown)
        focused("sidebar:search")
    }

    @Test fun editorClosesBackOntoItsButton() {
        setUp(Destinations.HOME)
        key(Key.DirectionLeft)
        // Up through Search wraps to Settings; Right reaches the editor.
        repeat(2) { key(Key.DirectionUp) }
        focused("sidebar:settings")
        key(Key.DirectionRight)
        focused("sidebar:order")
        key(Key.Enter)
        back()
        focused("sidebar:order")
    }

    @Test fun aSingleSystemIsTheMainLevel() {
        state = SidebarUiState(trailersEnabled = false, tvheadendEnabled = true, tvheadendSubmenuKeys = listOf("live", "guide"))
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        focused("sidebar:tvheadend:guide")
        rule.onAllNodesWithTag("sidebar:system:tvheadend", useUnmergedTree = true).assertCountEquals(0)
        // No parent level: Back returns straight to the content.
        back()
        rule.onNodeWithTag("content").assertIsFocused()
    }

    @Test fun configurationRowFollowsTheShortcutSwitch() {
        val configuration = rule.activity.getString(com.ambient.tv.R.string.nav_configuration)
        state = state.copy(settingsShortcutHidden = setOf("tvheadend"))
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        focused("sidebar:tvheadend:guide")
        rule.onAllNodesWithText(configuration, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun singleSystemShowsTheLevelEditorAndFocusReturnsToIt() {
        state = SidebarUiState(trailersEnabled = false, tvheadendEnabled = true, tvheadendSubmenuKeys = listOf("live", "guide"))
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        key(Key.DirectionDown)
        focused("sidebar:settings")
        key(Key.DirectionRight)
        focused("sidebar:order")
        key(Key.Enter)
        // The level editor (no rename field) opened for this system's entries.
        rule.onAllNodesWithText(rule.activity.getString(com.ambient.tv.R.string.sidebar_entry_order_hint), useUnmergedTree = true).assertCountEquals(1)
        back()
        focused("sidebar:order")
    }

    @Test fun systemPageEditsThatSystemsEntryOrder() {
        state = state.copy(entryOrder = mapOf("tvheadend" to listOf("tvheadend:recordings", "tvheadend:live", "tvheadend:guide")))
        setUp(Destinations.liveTvSection("guide"))
        key(Key.DirectionLeft)
        // Stored order is applied: Up from guide (last) reaches live.
        key(Key.DirectionUp)
        focused("sidebar:tvheadend:live")
        key(Key.DirectionUp)
        focused("sidebar:tvheadend:recordings")
        repeat(3) { key(Key.DirectionDown) }
        focused("sidebar:settings")
        key(Key.DirectionRight)
        focused("sidebar:order")
        key(Key.Enter)
        back()
        focused("sidebar:order")
    }
}
