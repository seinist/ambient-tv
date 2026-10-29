package com.ambient.tv.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsSearchLandingComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun delayedServerRowsStillOpenTheTabRequestedByTheConsumedSearch() {
        var query by mutableStateOf<String?>("Startseite Reihen")
        var composeRows by mutableStateOf(false)
        rule.setContent { ServerTabs(query, composeRows) }
        rule.waitForIdle()

        rule.runOnIdle { query = null }
        rule.waitForIdle()
        rule.runOnIdle { composeRows = true }
        rule.waitForIdle()

        rule.onNodeWithTag("home-content").assertExists()
        rule.onNodeWithTag("submenu-content").assertDoesNotExist()
    }

    @Test fun aSecondSearchUpdatesTabsThatAreAlreadyComposed() {
        var query by mutableStateOf<String?>("home widgets")
        rule.setContent { ServerTabs(query, composeRows = true) }
        rule.waitForIdle()
        rule.onNodeWithTag("home-content").assertExists()

        rule.runOnIdle { query = null }
        rule.waitForIdle()
        rule.runOnIdle { query = "Untermenü" }
        rule.waitForIdle()
        rule.runOnIdle { query = null }
        rule.waitForIdle()

        rule.onNodeWithTag("submenu-content").assertExists()
        rule.onNodeWithTag("home-content").assertDoesNotExist()
    }

    @Composable
    private fun ServerTabs(query: String?, composeRows: Boolean) {
        val landing = remember { SettingsSearchLanding() }
        CompositionLocalProvider(LocalSettingsSearchLanding provides landing.accept(query)) {
            if (composeRows) SystemCustomizationTabs(
                showStartPageTab = true,
                submenuContent = { Text("Submenu", Modifier.testTag("submenu-content")) },
                startPageContent = { Text("Home", Modifier.testTag("home-content")) },
            )
        }
    }
}
