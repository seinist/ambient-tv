package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production controls: native mobile defaults and TV focus must not invert text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class ControlContentTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvControlsKeepLightContentThroughoutFocusAndSelectionChanges() = verifyControls(false)

    @Test fun mobileControlsUseTheSameContentColorsAndKeepTouchTargets() = verifyControls(true)

    private fun verifyControls(handheld: Boolean) {
        var accent by mutableStateOf("#001020")
        var style by mutableStateOf("glass")
        var selected by mutableStateOf(true)
        var loading by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var expected = Color.Unspecified
        var muted = Color.Unspecified
        var tvSlot = Color.Unspecified
        var mobileSlot = Color.Unspecified
        var clicks = 0
        val actionFocus = FocusRequester()
        val chipFocus = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme(accentColorKey = "custom", customAccentHex = accent, accentStyleKey = style) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        expected = scheme.onSurface
                        muted = scheme.onSurfaceVariant.copy(alpha = .55f)
                    }
                    Column(Modifier.padding(24.dp)) {
                        TvActionButton("Play", { clicks++ }, Modifier.testTag("action").focusRequester(actionFocus),
                            selected = selected, enabled = enabled, loading = loading, icon = Icons.Default.PlayArrow)
                        TvChip("Autoplay", selected = selected,
                            modifier = Modifier.testTag("chip").focusRequester(chipFocus),
                            leadingContent = {
                                val tv = androidx.tv.material3.LocalContentColor.current
                                val mobile = androidx.compose.material3.LocalContentColor.current
                                SideEffect { tvSlot = tv; mobileSlot = mobile }
                                androidx.compose.material3.Icon(Icons.Default.PlayArrow, null)
                            }, onClick = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        for ((color, material) in listOf("#001020" to "glass", "#FFFFFF" to "pearl", "#E5A00D" to "light")) {
            rule.runOnIdle { accent = color; style = material; selected = true }
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            for ((focus, tag) in listOf(chipFocus to "chip", actionFocus to "action")) {
                rule.runOnIdle { focus.requestFocus() }
                repeat(6) {
                    rule.mainClock.advanceTimeBy(32)
                    assertEquals("Action content stays light during $material focus", expected, textColor("Play"))
                    assertEquals("Chip content stays light during $material focus", expected, textColor("Autoplay"))
                    assertEquals(expected, tvSlot)
                    assertEquals(expected, mobileSlot)
                }
                rule.onNodeWithTag(tag).assertIsFocused()
            }
            rule.mainClock.autoAdvance = true
            rule.runOnIdle { selected = false }
            rule.waitForIdle()
            assertEquals(expected, textColor("Play"))
            assertEquals(expected, textColor("Autoplay"))
        }
        if (handheld) {
            for (tag in listOf("action", "chip")) {
                val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("Controls retain their 48dp touch target", bounds.height >= 48f)
            }
        }
        fun activateAction() {
            if (handheld) rule.onNodeWithTag("action").performClick()
            else {
                // TV Surface activates through remote keys; performClick injects a touch.
                rule.runOnIdle { actionFocus.requestFocus() }
                rule.onNodeWithTag("action").assertIsFocused()
                rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            }
        }
        activateAction()
        rule.runOnIdle { assertEquals(1, clicks); enabled = false }
        rule.waitForIdle()
        assertEquals(muted, textColor("Play"))
        rule.runOnIdle { loading = true }
        rule.waitForIdle()
        assertEquals("A running action keeps its readable content", expected, textColor("Play"))
        activateAction()
        rule.runOnIdle { assertEquals("Running action cannot be started twice", 1, clicks) }
    }

    @Test fun tvChipMaintainsItsRestored52dpTarget() {
        rule.setContent {
            AmbientTvTheme {
                TvChip(
                    text = "Browse",
                    modifier = Modifier.testTag("full-size-tv-chip"),
                    icon = Icons.Default.PlayArrow,
                    onClick = {},
                )
            }
        }
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("full-size-tv-chip").fetchSemanticsNode().boundsInRoot
        assertTrue("TV chips must retain the restored >=52dp target", bounds.height >= 52f)
    }

    private fun textColor(label: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }
}
