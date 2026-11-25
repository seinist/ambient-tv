package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real pointer gestures exercise the shared rail used by every handheld browse page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h732dp-port", application = Application::class)
class AlphabetBarTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(initialLetters: List<Char>, initialSelected: Char?, val followSelection: Boolean) {
        val letters = mutableStateOf(initialLetters)
        val selected = mutableStateOf(initialSelected)
        val choices = mutableListOf<Char?>()
        val callbackVersion = mutableStateOf(0)
        val observedCallbackVersions = mutableListOf<Int>()
    }

    private fun mount(
        letters: List<Char> = ('A'..'Z').toList(),
        selected: Char? = 'A',
        followSelection: Boolean = true,
    ): Fixture {
        val fixture = Fixture(letters, selected, followSelection)
        val contentFocus = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Row(Modifier.fillMaxSize().testTag("alphabet-touch-root")) {
                        Box(Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusable())
                        val callbackVersion = fixture.callbackVersion.value
                        AlphabetBar(
                            letters = fixture.letters.value,
                            selected = fixture.selected.value,
                            onSelect = { letter ->
                                fixture.choices += letter
                                fixture.observedCallbackVersions += callbackVersion
                                if (fixture.followSelection) fixture.selected.value = letter
                            },
                            gridFocus = contentFocus,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun root() = rule.onNodeWithTag("alphabet-touch-root")
    private fun letter(value: Char) = rule.onNodeWithTag("alphabet-letter-$value", useUnmergedTree = true)
    private fun preview() = rule.onNodeWithTag("alphabet-preview", useUnmergedTree = true)

    private fun point(value: Char): Offset =
        letter(value).fetchSemanticsNode().boundsInRoot.center - root().fetchSemanticsNode().boundsInRoot.topLeft

    @Test fun pressingAndScrubbingShowsTheCurrentLetterWithoutDuplicateSelectionsOnRelease() {
        val fixture = mount()
        val middle = point('M')
        root().performTouchInput { down(middle) }
        preview().assertIsDisplayed().assertTextEquals("M")
        letter('M').assertIsSelected()
        assertTrue("The preview must be larger than its rail entry",
            preview().fetchSemanticsNode().boundsInRoot.height > letter('M').fetchSemanticsNode().boundsInRoot.height)

        root().performTouchInput { moveTo(middle + Offset(0f, 1f)) }
        rule.runOnIdle { assertEquals(listOf('M'), fixture.choices) }

        val end = point('Z')
        root().performTouchInput { moveTo(end) }
        preview().assertTextEquals("Z")
        letter('Z').assertIsSelected()
        letter('M').assertIsNotSelected()
        root().performTouchInput { up() }

        preview().assertDoesNotExist()
        letter('Z').assertIsSelected()
        rule.runOnIdle { assertEquals(listOf('M', 'Z'), fixture.choices) }
    }

    @Test fun fingerSelectionWinsWhileTheContentViewportReportsAnOlderLetter() {
        val fixture = mount(followSelection = false)
        val middle = point('M')
        root().performTouchInput { down(middle) }
        preview().assertTextEquals("M")
        letter('M').assertIsSelected()
        letter('A').assertIsNotSelected()

        // The list catches up asynchronously while the finger is still held on the rail.
        rule.runOnIdle { fixture.selected.value = 'B' }
        preview().assertTextEquals("M")
        letter('M').assertIsSelected()
        letter('B').assertIsNotSelected()
        val end = point('Z')
        root().performTouchInput { moveTo(end) }
        preview().assertTextEquals("Z")
        rule.runOnIdle { assertEquals(listOf('M', 'Z'), fixture.choices) }

        root().performTouchInput { up() }
        preview().assertDoesNotExist()
        letter('B').assertIsSelected()
    }

    @Test fun cancellationRemovesThePreviewAndTheNextTouchStartsANewSelection() {
        val fixture = mount()
        val middle = point('M')
        root().performTouchInput { down(middle) }
        preview().assertTextEquals("M")
        root().performTouchInput { cancel() }
        preview().assertDoesNotExist()

        val first = point('A')
        root().performTouchInput { down(first) }
        preview().assertTextEquals("A")
        root().performTouchInput { up() }
        preview().assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf('M', 'A'), fixture.choices) }
    }

    @Test fun callbackChangesDuringAScrubDoNotRestartTheGestureOrKeepTheOldCallback() {
        val fixture = mount()
        val middle = point('M')
        root().performTouchInput { down(middle) }
        preview().assertTextEquals("M")

        // Capture a new callback value at composition time, as browse-page closures do.
        rule.runOnIdle { fixture.callbackVersion.value = 1 }
        preview().assertTextEquals("M")
        rule.runOnIdle { assertEquals(listOf('M'), fixture.choices) }
        val end = point('Z')
        root().performTouchInput { moveTo(end) }
        preview().assertTextEquals("Z")
        root().performTouchInput { up() }

        preview().assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(listOf('M', 'Z'), fixture.choices)
            assertEquals(listOf(0, 1), fixture.observedCallbackVersions)
        }
    }

    @Test fun accessibleLetterClickStillWorksWithoutOpeningATouchPreview() {
        val fixture = mount()
        letter('M').performClick()
        letter('M').assertIsSelected()
        preview().assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf('M'), fixture.choices) }
    }

    @Test
    @Config(qualifiers = "w732dp-h300dp-land")
    fun compressedLandscapeRailKeepsSmallAndDottedSlotsSelectable() {
        val fixture = mount()
        val choices = listOf('A', 'B', 'M', 'Y', 'Z')
        val first = point(choices.first())
        root().performTouchInput { down(first) }
        for (value in choices) {
            if (value != choices.first()) {
                val target = point(value)
                root().performTouchInput { moveTo(target) }
            }
            preview().assertIsDisplayed().assertTextEquals(value.toString())
            letter(value).assertIsSelected()
        }
        root().performTouchInput { up() }
        preview().assertDoesNotExist()
        rule.runOnIdle { assertEquals(choices, fixture.choices) }
    }

    @Test fun emptyAndSingleLetterIndexesDoNotCreateInvalidSelections() {
        val fixture = mount(letters = emptyList(), selected = null)
        root().performTouchInput { click(Offset(width - 2f, height / 2f)) }
        preview().assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(fixture.choices.isEmpty())
            fixture.letters.value = listOf('#')
        }
        val only = point('#')
        root().performTouchInput { down(only); moveTo(only + Offset(0f, 1f)) }
        preview().assertIsDisplayed().assertTextEquals("#")
        root().performTouchInput { up() }
        preview().assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf('#'), fixture.choices) }
    }

    @Test fun changingTheIndexDuringAScrubCancelsItsPreviewAndDoesNotSelectFromTheNewList() {
        val fixture = mount(letters = listOf('A', 'B', 'C'))
        val last = point('C')
        root().performTouchInput { down(last) }
        preview().assertTextEquals("C")

        rule.runOnIdle { fixture.letters.value = listOf('X', 'Y', 'Z') }
        preview().assertDoesNotExist()
        root().performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf('C'), fixture.choices) }

        val replacement = point('Y')
        root().performTouchInput { down(replacement) }
        preview().assertTextEquals("Y")
        root().performTouchInput { up() }
        preview().assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf('C', 'Y'), fixture.choices) }
    }
}
