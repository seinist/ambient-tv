package com.ambient.tv.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Switching from a Settings area to a child page must not collapse the TV header. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1200dp-h720dp-land-mdpi")
class SettingsHeaderLabelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun mainSubpageAndAddCatalogKeepTheSameHeaderTitleAndActionBaseline() {
        val pageTitle = mutableStateOf<String?>(null)
        val addTitle = mutableStateOf<String?>(null)
        rule.setContent {
            AmbientTvTheme {
                SettingsHeaderRow(clockVisible = false, modifier = Modifier.testTag("settings-header")) {
                    Box(Modifier.size(32.dp))
                    SettingsHeaderLabel(
                        settingsTitle = "Settings",
                        areaTitle = "Systems",
                        pageTitle = pageTitle.value,
                        addSystemTitle = addTitle.value,
                        modifier = Modifier.weight(1f),
                    )
                    TvChip("Search", modifier = Modifier.testTag("settings-search"), onClick = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Systems").assertExists()
        val parentTitle = rule.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val parentActionTop = rule.onNodeWithTag("settings-search").fetchSemanticsNode().boundsInRoot.top

        rule.runOnIdle { pageTitle.value = "Plex" }
        rule.waitForIdle()
        rule.onNodeWithText("Systems › Plex").assertExists()
        val subpageTitle = rule.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val subpageActionTop = rule.onNodeWithTag("settings-search").fetchSemanticsNode().boundsInRoot.top
        assertEquals("The main Settings title keeps its position on a subpage", parentTitle.top, subpageTitle.top, 1.01f)
        assertEquals("The main Settings title keeps its text height on a subpage", parentTitle.height, subpageTitle.height, 1.01f)
        assertEquals("Switching to a subpage must not move the header actions", parentActionTop, subpageActionTop, 1.01f)

        rule.runOnIdle {
            pageTitle.value = null
            addTitle.value = "Add system"
        }
        rule.waitForIdle()
        rule.onNodeWithText("Systems › Add system").assertExists()
        val catalogTitle = rule.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        assertEquals("The Add catalog also shares the same title position", parentTitle.top, catalogTitle.top, 1.01f)
    }
}
