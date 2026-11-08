package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the nested viewport, not a duplicate of the key-routing implementation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class DetailDescriptionSectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val longText = (1..50).joinToString("\n") { "Paragraph $it of the complete description." }
    private val description = mutableStateOf(longText)
    private val actions = FocusRequester()
    private val text = FocusRequester()
    private lateinit var outerScroll: ScrollState

    private fun show() {
        rule.setContent {
            AmbientTvTheme {
                outerScroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(outerScroll)) {
                    Box(Modifier.fillMaxWidth().height(48.dp).focusRequester(actions)
                        .testTag("actions").focusable())
                    DetailDescriptionSection(
                        text = description.value,
                        actionFocus = actions,
                        maxHeight = 160.dp,
                        modifier = Modifier.focusRequester(text),
                    )
                    Box(Modifier.fillMaxWidth().height(48.dp).testTag("following").focusable())
                    Box(Modifier.height(700.dp))
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(text.requestFocus()) }
        rule.waitForIdle()
        rule.onNodeWithText(description.value).assertIsFocused()
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun textOffset(): Float = rule.onNodeWithText(description.value)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun readingScrollsOnlyTextAndReachesBothNeighbouringRegions() {
        show()
        val outerBefore = rule.runOnIdle { outerScroll.value }
        press(Key.PageDown)
        rule.onNodeWithText(longText).assertIsFocused()
        assertTrue(textOffset() > 0f)
        assertEquals(outerBefore, rule.runOnIdle { outerScroll.value })

        press(Key.PageUp)
        assertEquals(0f, textOffset(), 1f)
        press(Key.DirectionUp)
        rule.onNodeWithTag("actions").assertIsFocused()

        rule.runOnIdle { description.value = "Short complete description" }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(text.requestFocus()) }
        rule.waitForIdle()
        press(Key.DirectionDown)
        rule.onNodeWithTag("following").assertIsFocused()
    }

    @Test fun scrollbarReturnsToTextAndShorterMetadataKeepsOwnedFocus() {
        show()
        press(Key.DirectionRight)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.common_scrollbar)).assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNodeWithText(longText).assertIsFocused()

        press(Key.DirectionRight)
        rule.runOnIdle { description.value = "Enriched short description" }
        rule.waitForIdle()
        rule.onNodeWithText(description.value).assertIsFocused()
    }

    @Test fun metadataEnrichmentDoesNotStealFocusFromActions() {
        show()
        rule.runOnIdle { assertTrue(actions.requestFocus()) }
        rule.waitForIdle()
        rule.runOnIdle { description.value = longText + "\nAdditional source information." }
        rule.waitForIdle()
        rule.onNodeWithTag("actions").assertIsFocused()
    }
}
