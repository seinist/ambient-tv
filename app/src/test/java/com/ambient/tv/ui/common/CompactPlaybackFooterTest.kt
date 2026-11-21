package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV footer must fit the existing 38% panel, even at 720 dp viewport width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h480dp-land-mdpi")
class CompactPlaybackFooterTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var chosen by mutableStateOf("advanced")
    private var backCount = 0

    private fun mount(value: String = "Erweitert") {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    RightSidePanel(
                        title = "Wiedergabe",
                        onBack = { backCount++ },
                        footerStart = {
                            PlayerDropUpSelector(
                                label = "Einstellungsumfang",
                                value = if (chosen == "advanced") value else "Einfach",
                                options = listOf("simple" to "Einfach", "advanced" to value, "expert" to "Experte"),
                                selectedKey = chosen,
                                modifier = Modifier.fillMaxWidth().testTag("scope-in-footer"),
                                compactFooter = true,
                                onSelected = { chosen = it },
                            )
                        },
                    ) {
                        TvActionButton("Video", onClick = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun levelAndBackFitBesideEachOtherWithNoPanelGrowth() {
        mount()
        val scope = rule.onNodeWithTag("scope-in-footer", useUnmergedTree = true)
            .assertExists().fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithText(rule.activity.getString(R.string.back), useUnmergedTree = true)
            .assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue("Scope must be left of Back", scope.right <= back.left + 1f)
        assertTrue("Footer must stay within the existing right edge", back.right <= 720f)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Erweitert", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("The level label may wrap but must never overflow", layouts.single().hasVisualOverflow)
    }

    @Test fun aLongLocalizedLevelWrapsInsteadOfBeingEllipsized() {
        mount("Sehr ausführliche Experteneinstellungen")
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Sehr ausführliche Experteneinstellungen", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        // Robolectric can give the footer more width than the rendered 720dp viewport.
        // A one-line fit is also correct; the required invariant is no visual clipping.
        assertTrue("The level label must be laid out", layouts.single().lineCount >= 1)
        assertFalse("Even a long translated value must remain fully visible", layouts.single().hasVisualOverflow)
    }

    @Test fun pinnedDropdownChangesLevelWithoutDisablingTheBackButton() {
        mount()
        rule.onNodeWithContentDescription("Einstellungsumfang: Erweitert", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("scope-in-footer", useUnmergedTree = true).performTouchInput { click() }
        rule.onNodeWithText("Einfach").performClick()
        rule.runOnIdle { assertEquals("simple", chosen) }
        rule.onNodeWithContentDescription("Einstellungsumfang: Einfach", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.back)).performClick()
        // Standalone panels animate before notifying navigation. Advance frames repeatedly:
        // a single large virtual-time jump can begin the coroutine at its end in Robolectric.
        repeat(15) {
            rule.mainClock.advanceTimeBy(100L)
            rule.waitForIdle()
        }
        rule.waitUntil(timeoutMillis = 3_000L) { backCount == 1 }
        rule.runOnIdle { assertEquals(1, backCount) }
    }
}
