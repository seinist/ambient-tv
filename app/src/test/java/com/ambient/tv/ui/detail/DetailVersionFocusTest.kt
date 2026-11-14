package com.ambient.tv.ui.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.domain.model.MetadataEditSupport
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real page: the hero's parent must not consume Down before its versions. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class DetailVersionFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Playback(val ratingKey: String, val version: Int, val restart: Boolean)
    private val playback = mutableListOf<Playback>()

    @Before fun clearPageMemory() {
        DetailFocusSessions.clear()
    }

    private fun mount(
        disabled: Set<Int> = emptySet(),
        withFollowingSection: Boolean = false,
        longSummary: Boolean = false,
        versionCount: Int = 6,
        sameResolution: Boolean = false,
    ): MutableState<MediaItem> {
        val item = movie("movie", "Versioned film").copy(
            summary = if (longSummary) "A long film synopsis fills all five lines of the detail hero. ".repeat(40) else null,
            mediaVersions = (0 until versionCount).map { index ->
                MediaVersion(
                    streamUrl = if (index in disabled) null else "https://example.invalid/version-$index.mkv",
                    resolution = if (sameResolution) "1080" else null, videoCodec = null, audioCodec = null, container = null,
                    sourceRatingKey = "movie", sourceMediaIndex = index,
                    fileName = "Version-${index + 1}.mkv",
                )
            },
        )
        val itemState = mutableStateOf(item)
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    DetailContent(
                        playbackAvailable = true,
                        availabilityMessage = null,
                        onTrailer = null,
                        item = itemState.value,
                        focusScope = "version-focus-test",
                        children = emptyList(),
                        childrenLoading = false,
                        childrenLoadFailed = false,
                        seasons = emptyList(),
                        onRetryChildren = {},
                        nextEpisode = null,
                        similar = if (withFollowingSection) listOf(movie("related", "Following film")) else emptyList(),
                        similarLoading = false,
                        actorRows = emptyList(),
                        actionInProgress = false,
                        autoSelectDisplayVersion = false,
                        onPlay = { media, index, restart -> playback += Playback(media.ratingKey, index, restart) },
                        onPlayVersion = { media, index -> playback += Playback(media.ratingKey, index, false) },
                        onOpenChild = {},
                        onOpenEpisode = {},
                        onOpenSeason = {},
                        onOpenSeries = {},
                        onBack = {},
                        onOpenSettings = {},
                        onBrowseActor = { _, _ -> },
                        onBrowseCollection = { _, _ -> },
                        onSetWatched = {},
                        onRemoveFromContinue = {},
                        editSupport = MetadataEditSupport.NONE,
                        onMetadataEdited = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        return itemState
    }

    private fun action(label: String) = rule.onNode(hasText(label) and hasClickAction())
    private fun playAction() = action(rule.activity.getString(R.string.detail_play))
    private fun moreAction() = action(rule.activity.getString(R.string.detail_more_actions))

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun downReachesEveryVersionAndScrollsPastFourThenUpReturnsToPlay() {
        mount()
        playAction().assertIsFocused()
        action("#6").assertIsNotDisplayed()

        for (number in 1..6) {
            press(Key.DirectionDown)
            action("#$number").assertIsFocused().assertIsDisplayed()
            press(Key.DirectionCenter)
        }
        assertEquals((0 until 6).map { Playback("movie", it, false) }, playback)

        for (number in 5 downTo 1) {
            press(Key.DirectionUp)
            action("#$number").assertIsFocused().assertIsDisplayed()
        }
        press(Key.DirectionUp)
        playAction().assertIsFocused().assertIsDisplayed()
    }

    @Test fun twoIdenticalResolutionFilesRemainSeparatelyPlayableWithStableDpadFocus() {
        mount(versionCount = 2, sameResolution = true, longSummary = true)
        playAction().assertIsFocused()

        press(Key.DirectionDown)
        action("1080p #1").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        action("1080p #2").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        action("1080p #1").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionUp)
        playAction().assertIsFocused().assertIsDisplayed()

        assertEquals(listOf(Playback("movie", 0, false), Playback("movie", 1, false)), playback)
    }

    @Test fun duplicateResolutionLabelsNeverCollapseSourceFocusKeys() {
        val item = movie("movie", "Duplicates").copy(mediaVersions = listOf(
            MediaVersion("https://example.invalid/a", "1080", null, null, "mkv", sourceRatingKey = "movie", sourceMediaIndex = 0),
            MediaVersion("https://example.invalid/b", "1080", null, null, "mkv", sourceRatingKey = "movie", sourceMediaIndex = 1),
        ))
        assertEquals(listOf("1080p #1", "1080p #2"), detailVersionLabels(item.mediaVersions))
        assertEquals(2, detailVersionFocusKeys(item).distinct().size)
    }

    @Test fun disabledFirstMiddleAndLastSourcesAreSkippedWithoutChangingPlaybackIndices() {
        mount(disabled = setOf(0, 2, 5))
        // With the primary source unavailable, the existing More action owns initial focus.
        moreAction().assertIsFocused()
        listOf(1, 3, 6).forEach { action("#$it").assertIsNotEnabled() }

        listOf(2, 4, 5).forEach { number ->
            press(Key.DirectionDown)
            action("#$number").assertIsFocused().assertIsDisplayed()
            press(Key.DirectionCenter)
        }
        assertEquals(listOf(1, 3, 4).map { Playback("movie", it, false) }, playback)

        listOf(4, 2).forEach { number ->
            press(Key.DirectionUp)
            action("#$number").assertIsFocused().assertIsDisplayed()
        }
        press(Key.DirectionUp)
        moreAction().assertIsFocused()
    }

    @Test fun downLeavesTheLastVersionForTheFollowingSectionAndUpReturnsToIt() {
        mount(withFollowingSection = true)
        playAction().assertIsFocused()
        for (number in 1..6) {
            press(Key.DirectionDown)
            action("#$number").assertIsFocused()
        }

        press(Key.DirectionDown)
        action("Following film").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionUp)
        action("#6").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionCenter)
        assertEquals(listOf(Playback("movie", 5, false)), playback)
        press(Key.DirectionUp)
        action("#5").assertIsFocused().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-mdpi")
    fun aHeroTallerThanTheViewportKeepsBothScrollContainersNavigable() {
        mount(longSummary = true)
        playAction().assertIsFocused()
        action("#6").assertIsNotDisplayed()

        for (number in 1..6) {
            press(Key.DirectionDown)
            action("#$number").assertIsFocused().assertIsDisplayed()
        }
        for (number in 5 downTo 1) {
            press(Key.DirectionUp)
            action("#$number").assertIsFocused().assertIsDisplayed()
        }
        press(Key.DirectionUp)
        playAction().assertIsFocused().assertIsDisplayed()
    }

    @Test fun newlyDiscoveredAndReorderedVersionsKeepTheFocusedPhysicalSource() {
        val itemState = mount()
        repeat(3) { press(Key.DirectionDown) }
        action("#3").assertIsFocused()

        rule.runOnIdle {
            val item = itemState.value
            val newSource = item.mediaVersions.first().copy(
                sourceRatingKey = "another-copy",
                streamUrl = "https://example.invalid/new-copy.mkv",
                fileName = "New-copy.mkv",
            )
            itemState.value = item.copy(mediaVersions = listOf(newSource) + item.mediaVersions.reversed())
        }
        rule.waitForIdle()

        // The old third source is now fifth. OK must play it with the current list index.
        action("#5").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionCenter)
        assertEquals(listOf(Playback("movie", 4, false)), playback)
        press(Key.DirectionUp)
        action("#4").assertIsFocused().assertIsDisplayed()
    }

    @Test fun aSourceBecomingUnavailableMovesToTheNextPlayableSourceWithoutStartingPlayback() {
        val itemState = mount()
        repeat(3) { press(Key.DirectionDown) }
        action("#3").assertIsFocused()

        rule.runOnIdle {
            itemState.value = itemState.value.copy(
                mediaVersions = itemState.value.mediaVersions.mapIndexed { index, version ->
                    if (index == 2) version.copy(streamUrl = null) else version
                },
            )
        }
        rule.waitForIdle()
        action("#3").assertIsNotEnabled()
        action("#4").assertIsFocused().assertIsDisplayed()
        assertEquals(emptyList<Playback>(), playback)

        press(Key.DirectionUp)
        action("#2").assertIsFocused().assertIsDisplayed()
        press(Key.DirectionCenter)
        assertEquals(listOf(Playback("movie", 1, false)), playback)
    }

    private fun movie(key: String, title: String) = MediaItem(
        ratingKey = key, title = title, type = "movie", index = null,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )
}
