package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import com.ambient.tv.ui.common.TvLazyVerticalGrid as LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.SidebarActions
import com.ambient.tv.ui.navigation.SidebarContent
import com.ambient.tv.ui.navigation.SidebarContentEntry
import com.ambient.tv.ui.navigation.SidebarUiState
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real Sidebar entry protocol must reach a leaf through page/chip/grid focus groups. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class BrowseFocusReturnTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        var destination by mutableStateOf(Destinations.HOME)
        var itemIds by mutableStateOf((0 until 90).map { "item-$it" })
        var contentReady by mutableStateOf(true)
        val sidebar = FocusRequester()
        val owners = mutableMapOf<String, BrowseFocusReturnState>()
        val grids = mutableMapOf<String, LazyGridState>()
        var sidebarOwnsFocus = false
        var contentFocusPermit = false
        val entryResults = mutableListOf<Boolean?>()

        fun tileTag(id: String) = "$destination:tile:$id"
        fun chipTag(id: String) = "$destination:chip:$id"
        val grid: LazyGridState get() = checkNotNull(grids[destination])
    }

    private val sidebarActions = object : SidebarActions {
        override fun prefetch(route: String) = Unit
        override fun refresh(delayMs: Long) = Unit
        override fun applySystemOrder(order: List<String>) = Unit
        override fun renameSystems(names: Map<String, String>, edited: Set<String>) = Unit
        override fun toggleDivider(id: String) = Unit
        override fun applyEntryOrder(systemId: String, order: List<String>) = Unit
        override fun toggleEntryDivider(systemId: String, id: String) = Unit
    }

    private var currentFixture: Fixture? = null

    @Before fun clearPendingPageReselect() {
        NavReselect.restoreTop.value = false
    }

    private fun mount(initialTarget: String = "item-0", enterContent: Boolean = true): Fixture {
        val fixture = Fixture()
        currentFixture = fixture
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    Row(Modifier.fillMaxSize()) {
                        SidebarContent(
                            sidebarState = SidebarUiState(
                                trailersEnabled = true,
                                trailersConfigured = true,
                                filesEnabled = true,
                            ),
                            viewModel = sidebarActions,
                            currentRoute = fixture.destination,
                            currentSectionKey = null,
                            onNavigate = { fixture.destination = it },
                            contentReady = fixture.contentReady,
                            requestContentFocus = request@{
                                if (!fixture.contentReady) {
                                    fixture.entryResults += false
                                    return@request false
                                }
                                fixture.contentFocusPermit = true
                                try {
                                    SidebarContentEntry.requestFocus().also { fixture.entryResults += it } == true
                                } finally {
                                    fixture.contentFocusPermit = false
                                }
                            },
                            onFocusOwnershipChanged = { fixture.sidebarOwnsFocus = it },
                            modifier = Modifier.focusRequester(fixture.sidebar),
                        )
                        // These nested groups match the page/provider/grid ownership boundaries.
                        Box(Modifier.weight(1f).fillMaxSize().focusProperties {
                            // The real activity blocks incidental entry while the Sidebar closes;
                            // only its explicit handoff may enter through the nested page groups.
                            onEnter = {
                                if (!fixture.contentReady || (fixture.sidebarOwnsFocus && !fixture.contentFocusPermit)) cancelFocusChange()
                            }
                        }.focusGroup()) {
                            key(fixture.destination) {
                                BrowseFocusReturnScope {
                                    BrowsePage(fixture, initialTarget)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            if (enterContent) assertTrue(SidebarContentEntry.requestFocus() == true)
            else assertTrue(fixture.sidebar.requestFocus())
        }
        rule.waitForIdle()
        return fixture
    }

    @Composable
    private fun BrowsePage(fixture: Fixture, initialTarget: String) {
        val destination = fixture.destination
        val owner = checkNotNull(LocalBrowseFocusReturnState.current)
        val ids = fixture.itemIds
        val grid = rememberBrowseGridState(itemKeys = ids)
        val chain = rememberPageFocusChain()
        // Reordering the same content must not be mistaken for selecting a new filter/view.
        BrowseFocusContentIdentity("posters")
        BrowseFocusInitialTarget(if (destination == Destinations.HOME) initialTarget else ids.firstOrNull())
        SideEffect {
            fixture.owners[destination] = owner
            fixture.grids[destination] = grid
        }
        Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageHeader(
                    focusChain = chain,
                    showClock = false,
                    title = { Text("Library") },
                    actions = {
                        TvChip("Sort", modifier = Modifier.testTag("$destination:sort"), onClick = {})
                    },
                )
                PageChipRow(chain, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(listOf("All", "Unwatched"), key = { _, label -> label }) { index, label ->
                        TvChip(
                            text = label,
                            selected = index == 0,
                            modifier = Modifier.testTag("$destination:chip:$label")
                                .then(if (index == 0) Modifier.focusRequester(chain.selectedChip) else Modifier),
                            onClick = {},
                        )
                    }
                }
                Box(Modifier.weight(1f).focusGroup()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = grid,
                        modifier = Modifier.fillMaxSize().pageContentUp(chain).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(ids, key = { it }) { id ->
                            // Exercise the production target modifier without a provider/Hilt fixture.
                            Box(
                                Modifier.fillMaxWidth().height(88.dp)
                                    .testTag("$destination:tile:$id")
                                    .browseFocusTarget(id)
                                    .focusable(),
                            ) { Text(id) }
                        }
                    }
                }
            }
        }
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusDiagnostics(): String =
        "Sidebar entry results: ${currentFixture?.entryResults}; focused nodes: " +
            rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                .joinToString { it.config.toString() }

    private fun focused(tag: String) {
        try {
            rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}\n${focusDiagnostics()}", failure)
        }
    }

    private fun returnFromSidebar(fixture: Fixture) {
        val previousAttempts = fixture.entryResults.size
        press(Key.DirectionRight)
        // waitForIdle does not itself promise that the delayed Sidebar handoff has completed.
        // Wait for that production callback, including its asynchronous lazy-layout retries.
        try {
            rule.waitUntil(timeoutMillis = 2_000) {
                fixture.entryResults.drop(previousAttempts).any { it == true }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Sidebar handoff did not complete. ${focusDiagnostics()}", failure)
        }
        rule.waitForIdle()
    }

    private fun enterSidebarFromThirdRow(fixture: Fixture) {
        focused(fixture.tileTag("item-0"))
        repeat(2) { press(Key.DirectionDown) }
        focused(fixture.tileTag("item-6"))
        press(Key.DirectionLeft)
        focused("sidebar:home")
    }

    @Test fun rightReturnsToTheExactTileAcrossNestedFocusGroups() {
        val fixture = mount()
        enterSidebarFromThirdRow(fixture)
        returnFromSidebar(fixture)
        focused(fixture.tileTag("item-6"))
    }

    @Test fun aChipOriginStaysAChipEvenAfterContentHadFocus() {
        val fixture = mount()
        press(Key.DirectionDown)
        focused(fixture.tileTag("item-3"))
        press(Key.DirectionUp)
        focused(fixture.tileTag("item-0"))
        press(Key.DirectionUp)
        focused(fixture.chipTag("All"))
        press(Key.DirectionLeft)
        focused("sidebar:home")
        returnFromSidebar(fixture)
        focused(fixture.chipTag("All"))
    }

    @Test fun reorderingKeepsTheStableIdAndRestoresItsOffscreenLazyPosition() {
        val fixture = mount()
        enterSidebarFromThirdRow(fixture)
        rule.runOnIdle {
            fixture.itemIds = fixture.itemIds.filterNot { it == "item-6" } + "item-6"
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(fixture.grid.layoutInfo.visibleItemsInfo.any { it.key == "item-6" })
            assertTrue(fixture.sidebarOwnsFocus)
        }
        returnFromSidebar(fixture)
        focused(fixture.tileTag("item-6"))
        rule.onNodeWithTag(fixture.tileTag("item-6"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun removingThePinnedIdReturnsToTheCurrentFirstContentItem() {
        val fixture = mount()
        enterSidebarFromThirdRow(fixture)
        rule.runOnIdle { fixture.itemIds = fixture.itemIds.filterNot { it == "item-6" } }
        rule.waitForIdle()
        returnFromSidebar(fixture)
        focused(fixture.tileTag("item-0"))
        rule.onNodeWithTag(fixture.tileTag("item-6"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun replacingThePageCancelsItsPendingLazyReturnWithoutTakingFocus() {
        val fixture = mount(initialTarget = "item-84", enterContent = false)
        focused("sidebar:home")
        val oldOwner = checkNotNull(fixture.owners[Destinations.HOME])
        rule.runOnIdle {
            // This item has never been composed. Preparing it must not transfer focus by itself.
            assertNull(oldOwner.requestFocus())
            fixture.destination = Destinations.SEARCH
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(fixture.sidebarOwnsFocus)
            assertFalse(oldOwner.requestFocus() == true)
        }
        returnFromSidebar(fixture)
        focused(fixture.tileTag("item-0"))
        rule.mainClock.advanceTimeBy(250)
        rule.waitForIdle()
        focused(fixture.tileTag("item-0"))
    }

    @Test fun coldEntryFocusesTheFirstTileDespiteEarlierHeaderAndChipTargets() {
        val fixture = mount()
        focused(fixture.tileTag("item-0"))
        rule.runOnIdle { assertFalse(fixture.sidebarOwnsFocus) }
    }

    @Test fun leftCancelsAPendingHandoffAndLaterReadinessCannotTakeFocus() {
        val fixture = mount()
        enterSidebarFromThirdRow(fixture)
        rule.runOnIdle { fixture.contentReady = false }
        press(Key.DirectionRight)
        rule.waitUntil(timeoutMillis = 2_000) { fixture.entryResults.isNotEmpty() }
        focused("sidebar:pill")
        rule.runOnIdle {
            assertTrue(fixture.sidebarOwnsFocus)
            assertTrue(fixture.entryResults.all { it == false })
        }
        press(Key.DirectionLeft)
        focused("sidebar:home")
        val attemptsAfterCancel = fixture.entryResults.size
        rule.runOnIdle { fixture.contentReady = true }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        focused("sidebar:home")
        rule.runOnIdle {
            assertTrue(fixture.sidebarOwnsFocus)
            assertEquals(attemptsAfterCancel, fixture.entryResults.size)
        }
        // A later, explicit Right is still allowed to return to the original tile.
        returnFromSidebar(fixture)
        focused(fixture.tileTag("item-6"))
    }
}
