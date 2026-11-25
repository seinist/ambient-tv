package com.ambient.tv.ui.section

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.BrowseFocusReturnScope
import com.ambient.tv.ui.common.FilterBarSetting
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An ordinary focus/letter change must not traverse the offscreen library again. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class SectionGridFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun handheldLayoutsAcceptTheSameAlphabetJumpAfterManualScrolling() {
        val films = ('A'..'Z').flatMap { letter ->
            List(12) { index -> item((letter - 'A') * 12 + index, "$letter Film $index") }
        }
        val initial = SectionUiState.Loaded(
            title = "Films", page = SectionPage.LIBRARY, items = films,
            recommendationRows = emptyList(), collections = emptyList(),
            sort = SectionSort.TITLE, sortOrder = SectionSortOrder.ASCENDING,
            filter = SectionFilter.ALL, qualityFilter = SectionQualityFilter.ALL,
            viewMode = SectionViewMode.POSTER, focusedRatingKey = "film-0",
            availableLetters = ('A'..'Z').toList(),
        )
        var state by mutableStateOf(initial)
        val parkedFocus = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides true,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalFilterBarSetting provides FilterBarSetting(false) {},
                LocalPerformanceProfile provides PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = false),
            ) {
                AmbientTvTheme {
                    BrowseFocusReturnScope {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.size(1.dp).focusRequester(parkedFocus)
                                .testTag("parked-focus").focusable())
                            SectionContent(
                                state = state, system = MediaSystem.PLEX,
                                onItemClick = {}, onCollectionClick = { _, _ -> }, onOpenOverview = {}, onBack = null,
                                onPage = {}, onItemFocused = {}, onRecommendationItemFocused = { _, _ -> },
                                onCollectionFocused = {}, onSetSort = {}, onSetSortOrder = {},
                                onSetFilterGroup = { _, _ -> }, onResetFilters = {}, onSetView = {},
                                onSetGridColumns = {}, onSetListRows = {}, sectionKey = "alphabet-fixture",
                                onSelectLetter = { letter -> state = state.copy(
                                    selectedLetter = letter, alphabetJumpRevision = state.alphabetJumpRevision + 1,
                                ) },
                            )
                        }
                    }
                }
            }
        }

        for (mode in listOf(SectionViewMode.POSTER, SectionViewMode.LANDSCAPE,
            SectionViewMode.LIST, SectionViewMode.INFO_LIST, SectionViewMode.WALL)) {
            rule.runOnIdle { state = initial.copy(viewMode = mode) }
            rule.waitForIdle()
            rule.runOnIdle { parkedFocus.requestFocus() }
            rule.onNodeWithTag("alphabet-letter-M").performClick()
            rule.waitForIdle()
            tile("M Film 0").assertIsDisplayed()
            rule.onNodeWithTag("parked-focus").assertIsFocused()

            rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToIndex(0)
            rule.waitForIdle()
            tile("A Film 0").assertIsDisplayed()
            rule.runOnIdle { assertEquals('M', state.selectedLetter) }

            rule.onNodeWithTag("alphabet-letter-M").performClick()
            rule.waitForIdle()
            // Checking the actual item avoids a false pass from the rail's immediate marker.
            tile("M Film 0").assertIsDisplayed()
            rule.onNodeWithTag("parked-focus").assertIsFocused()
        }
    }

    @Test fun denseGridFocusAcrossAlphabetBucketsDoesNotRescanOffscreenFilms() {
        val films = CountingLibrary(List(2_048) { index ->
            item(index, "${'A' + index.coerceAtMost(25)}$index")
        })
        val state = SectionUiState.Loaded(
            title = "Films", page = SectionPage.LIBRARY, items = films,
            recommendationRows = emptyList(), collections = emptyList(),
            sort = SectionSort.TITLE, sortOrder = SectionSortOrder.ASCENDING,
            filter = SectionFilter.ALL, qualityFilter = SectionQualityFilter.ALL,
            viewMode = SectionViewMode.POSTER, focusedRatingKey = "film-0",
            availableLetters = ('A'..'Z').toList(), gridColumns = 12,
        )
        var focusedKey: String? = null
        var alphabetJumps = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalFilterBarSetting provides FilterBarSetting(false) {},
                LocalPerformanceProfile provides PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = false),
            ) {
                AmbientTvTheme {
                    BrowseFocusReturnScope {
                        SectionContent(
                            state = state, system = MediaSystem.PLEX,
                            onItemClick = {}, onCollectionClick = { _, _ -> }, onOpenOverview = {}, onBack = null,
                            onPage = {}, onItemFocused = { focusedKey = it.ratingKey },
                            onRecommendationItemFocused = { _, _ -> }, onCollectionFocused = {},
                            onSetSort = {}, onSetSortOrder = {}, onSetFilterGroup = { _, _ -> }, onResetFilters = {},
                            onSetView = {}, onSetGridColumns = {}, onSetListRows = {},
                            onSelectLetter = { alphabetJumps++ }, sectionKey = "fixture",
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        tile("A0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.waitForIdle()
        tile("A0").assertIsFocused()
        rule.runOnIdle { films.offscreenReads = 0 }

        tile("A0").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        tile("B1").assertIsFocused()
        tile("B1").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        tile("C2").assertIsFocused()
        tile("C2").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        tile("B1").assertIsFocused()
        rule.runOnIdle {
            assertEquals("film-1", focusedKey)
            assertEquals("Tracking focus must not trigger an A–Z scroll command", 0, alphabetJumps)
            assertEquals("Visible focus hops must not rebuild keys or scan the full library", 0, films.offscreenReads)
        }
    }

    private fun tile(title: String) = rule.onNode(hasText(title) and hasClickAction())

    private class CountingLibrary(private val entries: List<MediaItem>) : AbstractList<MediaItem>() {
        var offscreenReads = 0
        override val size: Int get() = entries.size
        override fun get(index: Int): MediaItem {
            // Far beyond the viewport, prefetch and nearest-key range of a 12-column grid.
            if (index >= 512) offscreenReads++
            return entries[index]
        }
    }

    private fun item(index: Int, title: String) = MediaItem(
        ratingKey = "film-$index", title = title, type = "movie", index = null,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )
}
