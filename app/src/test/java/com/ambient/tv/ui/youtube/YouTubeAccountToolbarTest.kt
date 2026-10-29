package com.ambient.tv.ui.youtube

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.ViewOptionsChip
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.youtube.YouTubeAccountProfile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-port-mdpi")
class YouTubeAccountToolbarTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun signedInAccountLeavesRoomForSearchReloadAndViewOnANarrowPhone() {
        val accountName = "A very long account name that must remain accessible without crowding the toolbar"
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides true,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    PageHeader(Modifier.width(288.dp).testTag("header"), actions = {
                        YouTubeAccountButton(
                            YouTubeUiState(loading = false, signedIn = true,
                                accountProfile = YouTubeAccountProfile(accountName, null)),
                            onSignIn = {}, onSignOut = {},
                        )
                        TvChip("Search", icon = Icons.Filled.Search, iconOnly = true, onClick = {})
                        TvChip("Reload", icon = Icons.Filled.Refresh, iconOnly = true, onClick = {})
                        ViewOptionsChip(0, {})
                    }) { Text("YouTube") }
                }
            }
        }
        rule.waitForIdle()
        val labels = listOf(accountName, "Search", "Reload", rule.activity.getString(R.string.action_view))
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val actions = labels.map { label ->
            rule.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        actions.forEach { bounds ->
            assertTrue("Every action keeps a 48dp touch target", bounds.width >= 48f && bounds.height >= 48f)
            assertTrue("Every action fits the available width", bounds.left >= header.left && bounds.right <= header.right)
        }
        actions.zipWithNext().forEach { (left, right) ->
            assertTrue("Actions retain their order without overlapping", left.right <= right.left)
        }
        rule.onNodeWithText(accountName).assertDoesNotExist()
    }
}
