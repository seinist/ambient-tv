package com.ambient.tv.ui.section

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.FilterBarSetting
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.home.SystemOverviewHeader
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class BrowseReloadControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun action(resource: Int): SemanticsNodeInteraction {
        val label = rule.activity.getString(resource)
        return rule.onNode((hasText(label, substring = true) or hasContentDescription(label, substring = true)) and hasClickAction())
    }

    private fun SemanticsNodeInteraction.activateOnRemote() {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        assertIsFocused()
        performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun sectionReloadKeepsHeaderFocusWhenAnEmptyLibraryReceivesItems() {
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        var state by mutableStateOf(SectionUiState.Loaded(
            title = "Films", page = SectionPage.LIBRARY, items = emptyList(),
            recommendationRows = emptyList(), collections = emptyList(),
            sort = SectionSort.YEAR, sortOrder = SectionSortOrder.DESCENDING,
            filter = SectionFilter.UNWATCHED, qualityFilter = SectionQualityFilter.ALL,
            viewMode = SectionViewMode.LIST,
        ))
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalFilterBarSetting provides FilterBarSetting(false) {},
            ) {
                AmbientTvTheme {
                    SectionContent(
                        state = state, system = MediaSystem.PLEX,
                        onItemClick = {}, onCollectionClick = { _, _ -> }, onOpenOverview = {}, onBack = null,
                        onPage = { state = state.copy(page = it) }, onItemFocused = {},
                        onRecommendationItemFocused = { _, _ -> }, onCollectionFocused = {},
                        onSetSort = {}, onSetSortOrder = {}, onSetFilterGroup = { _, _ -> }, onResetFilters = {},
                        onSetView = {}, onSetGridColumns = {}, onSetListRows = {}, onSelectLetter = {},
                        sectionKey = "fixture", refreshing = refreshing,
                        onRefresh = { refreshes++; refreshing = true },
                    )
                }
            }
        }
        rule.waitForIdle()
        val reload = action(R.string.action_reload)
        val reloadBounds = reload.fetchSemanticsNode().boundsInRoot
        assertTrue(reloadBounds.left >= action(R.string.nav_overview).fetchSemanticsNode().boundsInRoot.right)
        assertTrue(action(R.string.action_view).fetchSemanticsNode().boundsInRoot.left >= reloadBounds.right)

        reload.activateOnRemote()
        rule.waitForIdle()
        reload.assertIsFocused()
        reload.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("Busy Reload ignores repeated activation", 1, refreshes) }

        rule.runOnIdle {
            state = state.copy(items = listOf(item()), focusedRatingKey = "new")
            refreshing = false
        }
        rule.waitForIdle()
        reload.assertIsFocused()
        rule.runOnIdle {
            assertEquals(SectionPage.LIBRARY, state.page)
            assertEquals(SectionFilter.UNWATCHED, state.filter)
            assertEquals(SectionSort.YEAR, state.sort)
        }
    }

    @Test fun providerReloadStaysBetweenHomeAndViewAndKeepsFocusWhileBusy() {
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    SystemOverviewHeader(
                        system = MediaSystem.JELLYFIN, onOpenHome = {}, onOpenOptions = {},
                        onRefresh = { refreshes++; refreshing = true }, refreshing = refreshing,
                    )
                }
            }
        }
        rule.waitForIdle()
        val reload = action(R.string.action_reload)
        val initialView = action(R.string.action_view).fetchSemanticsNode().boundsInRoot
        val reloadBounds = reload.fetchSemanticsNode().boundsInRoot
        assertTrue(reloadBounds.left >= action(R.string.nav_home).fetchSemanticsNode().boundsInRoot.right)
        assertTrue(initialView.left >= reloadBounds.right)
        reload.activateOnRemote()
        rule.waitForIdle()
        reload.assertIsFocused()
        reload.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, refreshes) }
        rule.runOnIdle { refreshing = false }
        rule.waitForIdle()
        reload.assertIsFocused()
        assertEquals(initialView.right, action(R.string.action_view).fetchSemanticsNode().boundsInRoot.right, 1f)
        reload.performKeyInput { pressKey(Key.DirectionRight) }
        action(R.string.action_view).assertIsFocused()
    }

    private fun item() = MediaItem(
        ratingKey = "new", title = "New film", type = "movie", index = null,
        thumbUrl = null, artUrl = null, year = 2026, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )
}
