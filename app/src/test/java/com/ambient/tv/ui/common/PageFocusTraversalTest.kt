package com.ambient.tv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D-pad navigation between actual page stages, including dynamically hidden controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PageFocusTraversalTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val header = FocusRequester()
    private val filterVisible = mutableStateOf(true)

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun page() {
        rule.setContent {
            AmbientTvTheme {
                val chain = rememberPageFocusChain()
                val search = remember { FocusRequester() }
                val filter = remember { FocusRequester() }
                Column(Modifier.fillMaxSize()) {
                    PageHeader(focusChain = chain, actions = {
                        Box(Modifier.size(64.dp).focusRequester(header).testTag("header").focusable())
                    }) { androidx.tv.material3.Text("Navigation") }
                    Row(Modifier.pageFocusStage(chain, PageFocusLevel.SEARCH_TOP, search)) {
                        Box(Modifier.size(64.dp).testTag("search").focusable())
                    }
                    PageChipRow(chain) {
                        item {
                            Box(Modifier.size(64.dp).focusRequester(chain.selectedChip)
                                .testTag("chips").focusable())
                        }
                    }
                    if (filterVisible.value) {
                        Row(Modifier.pageFocusStage(chain, PageFocusLevel.FILTER, filter)) {
                            Box(Modifier.size(64.dp).testTag("filter").focusable())
                        }
                    }
                    Column(Modifier.pageContentUp(chain).focusGroup()) {
                        Box(Modifier.size(64.dp).testTag("content").focusable())
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { header.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("header").assertIsFocused()
    }

    @Test fun downAndUpVisitAllPresentStagesInOrder() {
        page()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("search").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("chips").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("filter").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("content").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("filter").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("chips").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("search").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("header").assertIsFocused()
    }

    @Test fun hiddenFilterStageIsSkippedInBothDirections() {
        filterVisible.value = true
        page()
        rule.runOnIdle { filterVisible.value = false }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("search").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("chips").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("content").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("chips").assertIsFocused()
    }

    private class CategoryChipFixture {
        val selection = mutableIntStateOf(0)
        val firstChip = FocusRequester()
        val content = FocusRequester()
        lateinit var focusChain: PageFocusChain
    }

    private fun categoryChipPage(): CategoryChipFixture {
        val fixture = CategoryChipFixture()
        rule.setContent {
            AmbientTvTheme {
                val chain = rememberPageFocusChain()
                fixture.focusChain = chain
                Column {
                    PageChipRow(chain, selectedIndex = fixture.selection.intValue) {
                        items(listOf(0, 1, 2), key = { it }) { index ->
                            Box(
                                Modifier.size(64.dp)
                                    .then(if (index == fixture.selection.intValue)
                                        Modifier.focusRequester(chain.selectedChip) else Modifier)
                                    .then(if (index == 0) Modifier.focusRequester(fixture.firstChip) else Modifier)
                                    .testTag("category-$index")
                                    .focusable(),
                            )
                        }
                    }
                    Box(Modifier.size(96.dp).focusRequester(fixture.content)
                        .testTag("category-content").focusable())
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    @Test fun switchedCategoryFollowsSelectedChipWhileChipRowOwnsFocus() {
        val page = categoryChipPage()
        rule.runOnIdle { page.firstChip.requestFocus() }
        rule.onNodeWithTag("category-0").assertIsFocused()

        // Asynchronous SmartTube/Radio category selection updates the active chip after
        // the row's focusRestorer has remembered an older category.
        rule.runOnIdle { page.selection.intValue = 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("category-2").assertIsFocused()
    }


    @Test fun confirmingCategoryKeepsThePressedChipWhileSelectionAndRowsUpdate() {
        assertConfirmedCategoryNeverVisitsAnotherChip(immediateSelection = false)
    }

    @Test fun immediateCategorySelectionNeverFlashesThePreviousChip() {
        assertConfirmedCategoryNeverVisitsAnotherChip(immediateSelection = true)
    }

    private fun assertConfirmedCategoryNeverVisitsAnotherChip(immediateSelection: Boolean) {
        val selected = mutableIntStateOf(0)
        val requested = mutableIntStateOf(-1)
        val rowsRevision = mutableIntStateOf(0)
        val first = FocusRequester()
        // Final-focus assertions miss a brief wrong focus repaired by the chip hold.
        // Record every acquisition, including ones between rendered frames.
        val focusVisits = mutableListOf<Int>()
        rule.setContent {
            AmbientTvTheme {
                val chain = rememberPageFocusChain()
                Column {
                    PageChipRow(chain, selectedIndex = selected.intValue) {
                        items((0..2).toList(), key = { it }) { index ->
                            TvChip(
                                text = "Category $index",
                                selected = index == selected.intValue,
                                modifier = Modifier.testTag("clicked-category-$index")
                                    .onFocusChanged { if (it.isFocused) focusVisits += index }
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .then(if (index == selected.intValue)
                                        Modifier.focusRequester(chain.selectedChip) else Modifier),
                                onClick = {
                                    requested.intValue = index
                                    if (immediateSelection) {
                                        selected.intValue = index
                                        rowsRevision.intValue++
                                    }
                                },
                            )
                        }
                    }
                    if (rowsRevision.intValue > 0) {
                        Box(Modifier.size(96.dp).testTag("loaded-content").focusable())
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { first.requestFocus() }
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("clicked-category-1").assertIsFocused()
        rule.runOnIdle { focusVisits.clear() }
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle { check(requested.intValue == 1) }
        rule.onNodeWithTag("clicked-category-1").assertIsFocused()

        if (!immediateSelection) {
            // The server may confirm the selection later and replace the content below.
            rule.runOnIdle {
                selected.intValue = 1
                rowsRevision.intValue++
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("clicked-category-1").assertIsFocused()
        rule.onNodeWithTag("clicked-category-0").assertExists()
        rule.runOnIdle {
            assertTrue("Confirmation briefly focused another category: $focusVisits",
                focusVisits.all { it == 1 })
        }
    }


    @Test fun delayedCategorySelectionMustNotUndoASecondDpadMove() {
        val selected = mutableIntStateOf(0)
        val requested = mutableIntStateOf(-1)
        val first = FocusRequester()
        val focusVisits = mutableListOf<Int>()
        rule.setContent {
            AmbientTvTheme {
                val chain = rememberPageFocusChain()
                PageChipRow(chain, selectedIndex = selected.intValue) {
                    items((0..2).toList(), key = { it }) { index ->
                        TvChip(
                            text = "Category $index",
                            selected = index == selected.intValue,
                            modifier = Modifier.testTag("fast-category-$index")
                                .onFocusChanged { if (it.isFocused) focusVisits += index }
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .then(if (index == selected.intValue)
                                    Modifier.focusRequester(chain.selectedChip) else Modifier),
                            onClick = { requested.intValue = index },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { first.requestFocus() }
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("fast-category-1").assertIsFocused()
        rule.runOnIdle { focusVisits.clear() }
        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        rule.runOnIdle { check(requested.intValue == 1) }
        rule.runOnIdle {
            assertTrue("Confirmation briefly focused another category: $focusVisits",
                focusVisits.all { it == 1 })
        }
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("fast-category-2").assertIsFocused()

        rule.runOnIdle {
            focusVisits.clear()
            selected.intValue = 1
        }
        rule.waitForIdle()
        rule.onNodeWithTag("fast-category-2").assertIsFocused()
        rule.runOnIdle {
            assertTrue("Delayed selection briefly undid the later D-pad move: $focusVisits",
                focusVisits.all { it == 2 })
        }
    }

    @Test fun externalCategoryChangeDoesNotStealContentFocusAndRestoresSelectedChipOnReturn() {
        val page = categoryChipPage()
        rule.runOnIdle { page.firstChip.requestFocus() }
        rule.waitForIdle()
        rule.runOnIdle { page.content.requestFocus() }
        rule.onNodeWithTag("category-content").assertIsFocused()

        // Sidebar selection can change the category while focus remains in the content.
        rule.runOnIdle { page.selection.intValue = 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("category-content").assertIsFocused()

        rule.runOnIdle { page.focusChain.chips.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("category-2").assertIsFocused()
    }

    @Test fun offscreenSelectedCategoryIsFocusedAfterLazyChipRowScroll() {
        val selected = mutableIntStateOf(0)
        val first = FocusRequester()
        rule.setContent {
            AmbientTvTheme {
                val chain = rememberPageFocusChain()
                PageChipRow(chain, modifier = Modifier.width(96.dp), selectedIndex = selected.intValue) {
                    items((0..7).toList(), key = { it }) { index ->
                        Box(
                            Modifier.size(64.dp)
                                .then(if (index == selected.intValue)
                                    Modifier.focusRequester(chain.selectedChip) else Modifier)
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .testTag("offscreen-category-$index")
                                .focusable(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { first.requestFocus() }
        rule.onNodeWithTag("offscreen-category-0").assertIsFocused()

        // A selected chip far beyond the LazyRow viewport must be attached before focus
        // returns to it, even if scrolling temporarily detaches the old focused chip.
        rule.runOnIdle { selected.intValue = 7 }
        rule.waitForIdle()
        rule.onNodeWithTag("offscreen-category-7").assertIsFocused()
    }

    @Test fun rightFromContentWithoutScrollbarStaysInBrowseArea() {
        val content = FocusRequester()
        rule.setContent {
            AmbientTvTheme {
                Column(Modifier.fillMaxSize()) {
                    PageHeader(actions = {
                        Box(Modifier.size(60.dp).testTag("header").focusable())
                    }) { androidx.tv.material3.Text("Browse") }
                    TvLazyColumn(allowUpExit = true, modifier = Modifier.fillMaxSize()) {
                        item {
                            Box(Modifier.size(96.dp).focusRequester(content)
                                .testTag("content").focusable())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { content.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("content").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("content").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("header").assertIsFocused()
    }

    @Test fun rightAtEndOfRegisteredHorizontalRowWrapsWithoutHeader() {
        val last = FocusRequester()
        rule.setContent {
            AmbientTvTheme {
                val rowFocus = rememberRowColumnFocus()
                val state = rememberLazyListState()
                RegisterRowColumnFocus(rowFocus, "row", state, listOf("a", "b", "c"))
                Column(Modifier.rowColumnFocusWrapRight(rowFocus)) {
                    Box(Modifier.size(64.dp).testTag("header").focusable())
                    LazyRow(state = state, modifier = Modifier.containRowFocusExit()) {
                        items(listOf("a", "b", "c")) { key ->
                            Box(Modifier.size(96.dp).rowColumnFocusTarget(rowFocus, "row", key)
                                .then(if (key == "c") Modifier.focusRequester(last) else Modifier)
                                .testTag(key).focusable())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { last.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("c").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("a").assertIsFocused()
    }

    @Test fun leftAtStartOfHeroRowWrapsToOffscreenLastCardInsteadOfSidebar() {
        val first = FocusRequester()
        val keys = (0..39).map { "card-$it" }
        rule.setContent {
            AmbientTvTheme {
                val rowFocus = rememberRowColumnFocus()
                val state = rememberLazyListState()
                RegisterRowColumnFocus(rowFocus, "hero", state, keys)
                Row {
                    Box(Modifier.size(64.dp).testTag("sidebar").focusable())
                    Column(Modifier.rowColumnFocusWrapRight(rowFocus, wrapLeft = true)) {
                        LazyRow(
                            state = state,
                            modifier = Modifier.width(192.dp).containRowFocusExit(),
                        ) {
                            items(keys) { key ->
                                Box(Modifier.size(96.dp)
                                    .rowColumnFocusTarget(rowFocus, "hero", key)
                                    .then(if (key == "card-0") Modifier.focusRequester(first) else Modifier)
                                    .testTag(key).focusable())
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { first.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("card-0").assertIsFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("card-39").assertIsFocused()
        // The ordinary Right wrap remains symmetrical even after a long horizontal scroll.
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("card-0").assertIsFocused()
    }

}
