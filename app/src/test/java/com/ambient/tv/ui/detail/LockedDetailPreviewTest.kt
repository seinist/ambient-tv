package com.ambient.tv.ui.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class LockedDetailPreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun cachedMetadataIsReadableButNoPlaybackPeopleOrCollectionActionsExist() {
        val checking = mutableStateOf(false)
        var retries = 0
        var backs = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    LockedDetailPreview(item(), checking.value, removed = false,
                        onRetry = { retries++; checking.value = true }, onBack = { backs++ }, onOpenSettings = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Cached film")[0].assertIsDisplayed()
        rule.onNodeWithText("A saved synopsis.").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.offline_detail_connection_required)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.detail_play)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.detail_resume)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.detail_cast)).assertDoesNotExist()
        rule.onNodeWithText("Cached author").assertDoesNotExist()
        rule.onNodeWithText("Cached collection").assertDoesNotExist()

        // Android's performClick injects touch. TV Surface uses pointer input for focus;
        // activate the focused control through the remote path this test is verifying.
        rule.onNode(hasText(rule.activity.getString(R.string.offline_detail_retry)) and hasClickAction())
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNode(hasText(rule.activity.getString(R.string.offline_detail_checking)) and hasClickAction())
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNode(hasText(rule.activity.getString(R.string.offline_detail_checking)) and hasClickAction())
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNode(hasText(rule.activity.getString(R.string.back)) and hasClickAction() and !hasTestTag("utility-header-back"))
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun aPersonWithoutAPortraitKeepsTheirNameRoleAndNavigation() {
        val opened = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    CastSection(item(), onBrowseActor = { id, _ -> opened += id }, restoreFocusKey = null, onFocused = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Cached author").assertIsDisplayed()
        rule.onNodeWithText("Writer").assertIsDisplayed()
        val author = rule.onNode(hasText("Cached author") and hasClickAction())
        author.performTouchInput { click() }
        author.assertIsFocused()
        rule.runOnIdle { assertEquals(emptyList<String>(), opened) }
        author.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(listOf("author"), opened) }
    }

    private fun item() = MediaItem(
        ratingKey = "movie", title = "Cached film", type = "movie", index = null,
        thumbUrl = null, artUrl = null, year = 2024, summary = "A saved synopsis.", tagline = null,
        durationMs = 5_400_000, viewOffsetMs = 120_000, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
        cast = listOf(CastMember("author", "Cached author", "Writer", null)),
        collections = listOf(MediaTag("collection", "Cached collection")),
    )
}
