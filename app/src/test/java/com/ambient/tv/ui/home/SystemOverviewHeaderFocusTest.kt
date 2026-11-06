package com.ambient.tv.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.ui.common.BrowseFocusReturnScope
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.browseFocusTarget
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provider overview header floats over a tall hero. Down must enter a media tile,
 * never restore its own last-focused action via the sidebar entry protocol.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class SystemOverviewHeaderFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun mount(system: MediaSystem) {
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
            ) {
                AmbientTvTheme {
                    BrowseFocusReturnScope {
                        Box(Modifier.fillMaxSize()) {
                            SystemOverviewHeader(
                                system = system,
                                onOpenHome = {}, onOpenOptions = {}, onRefresh = {}, refreshing = false,
                                optionsModifier = Modifier.testTag("overview-options"),
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                            Column(
                                Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 110.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Box(
                                    Modifier.size(150.dp, 70.dp)
                                        .browseFocusTarget("tile-0", rowKey = "recent")
                                        .testTag("tile-0")
                                        .focusable(),
                                )
                                Box(
                                    Modifier.size(150.dp, 70.dp)
                                        .browseFocusTarget("tile-1", rowKey = "recent")
                                        .testTag("tile-1")
                                        .focusable(),
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun focus(tag: String) {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.waitForIdle()
        rule.onNodeWithTag(tag).assertIsFocused()
    }

    private fun downFromHeader(expected: String) {
        focus("overview-options")
        rule.onNodeWithTag("overview-options").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(expected).assertIsFocused()
    }

    @Test fun plexHeaderDownEntersFirstMediaTile() {
        mount(MediaSystem.PLEX)
        downFromHeader("tile-0")
    }

    @Test fun jellyfinHeaderDownRestoresLastFocusedMediaTileNotItsOwnHeader() {
        mount(MediaSystem.JELLYFIN)
        focus("tile-1")
        downFromHeader("tile-1")
    }

    @Test fun embyHeaderDownRestoresContentAfterReturningToTopActions() {
        mount(MediaSystem.EMBY)
        downFromHeader("tile-0")
        focus("tile-1")
        downFromHeader("tile-1")
    }
}
