package com.ambient.tv.ui.section

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
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
