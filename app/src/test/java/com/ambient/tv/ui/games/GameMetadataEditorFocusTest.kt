package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.games.metadata.GameArtworkKind
import com.ambient.tv.games.metadata.GameMetadata
import com.ambient.tv.games.metadata.GameMetadataCandidate
import com.ambient.tv.games.metadata.GameMetadataProviderIds
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AccentStyle
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A focused semantics node alone did not reveal the missing visible focus treatment. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameMetadataEditorFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var style by mutableStateOf(AccentStyle.LIGHT)
    private var enabled by mutableStateOf(true)
    private lateinit var inputModeManager: InputModeManager
    private val selected = mutableListOf<Int>()

    private fun mount(imageKind: GameArtworkKind? = null, handheld: Boolean = false) {
        val choices = (0..1).map { index -> GameMetadataCandidate(GameMetadata(
            provider = GameMetadataProviderIds.THEGAMESDB,
            providerGameId = index.toString(), title = "Matching game $index", year = 1994,
        )) }
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme(accentStyleKey = style.key, accentMotionKey = "off") {
                    AppDialog(onDismiss = {}, title = "Choose game", scrollable = false) {
                        // The dialog owns the focused Compose window, separate from the activity.
                        inputModeManager = LocalInputModeManager.current
                        TvLazyColumn(Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(choices.indices.toList()) { index ->
                                GameMetadataMatchRow(choices[index], imageKind, preview = null,
                                    enabled = enabled, onClick = { selected += index },
                                    modifier = Modifier.testTag("match-$index"))
                            }
                        }
                    }
                }
            }
        }
        rule.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(InputMode.Keyboard, inputModeManager.inputMode) }
    }

    private fun match(index: Int) = rule.onNodeWithTag("match-$index")

    private fun settleFocus() {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun focus(index: Int) {
        match(index).performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        settleFocus()
        match(index).assertIsFocused()
    }

    private fun edgeColor(index: Int): Color {
        val pixels = match(index).captureToImage().toPixelMap()
        // Straight top edge, inside the 2dp stroke and away from rounded corners/content.
        return pixels[pixels.width / 2, 1]
    }

    private fun distance(first: Color, second: Color): Float =
        abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)

    private fun assertOutlineVisible(index: Int, idle: Color, message: String) {
        val focused = edgeColor(index)
        val difference = distance(focused, idle)
        assertTrue("$message: focused=$focused, idle=$idle, RGB difference=$difference", difference > .15f)
    }

    private fun assertEdgeUnchanged(index: Int, expected: Color, message: String) {
        val actual = edgeColor(index)
        val difference = distance(actual, expected)
        assertTrue("$message: actual=$actual, expected=$expected, RGB difference=$difference", difference < .04f)
    }

    @Test fun queryCanBeFocusedAndEditedWithRemote() {
        var query by mutableStateOf("Original game")
        var submitted = ""
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme(accentMotionKey = "off") {
                    AppDialog(onDismiss = {}, title = "Identify game", scrollable = false,
                        captureInitialFocus = false) {
                        inputModeManager = LocalInputModeManager.current
                        TvLazyColumn(Modifier.heightIn(max = 360.dp), wrapVerticalFocus = false) {
                            item("query") { GameMetadataSearchField(query, { query = it }, Modifier.testTag("query")) }
                            item("provider") { TvChip("Provider", onClick = {}, modifier = Modifier.testTag("provider")) }
                            item("search") { TvActionButton("Search", { submitted = query }, Modifier.testTag("search")) }
                        }
                    }
                }
            }
        }
        rule.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        val field = rule.onNodeWithTag("query")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        settleFocus()
        field.assertIsFocused()
        field.performKeyInput { pressKey(Key.DirectionCenter) }
        settleFocus()
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextReplacement("Different game")
        rule.runOnIdle { assertEquals("Different game", query) }
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionDown) }
        settleFocus()
        rule.onNodeWithTag("provider").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        settleFocus()
        rule.onNodeWithTag("search").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("Different game", submitted) }
        rule.onNodeWithTag("search").performKeyInput { pressKey(Key.DirectionUp) }
        settleFocus()
        rule.onNodeWithTag("provider").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        settleFocus()
        field.assertIsFocused()
    }

    @Test fun visibleFocusFollowsDpadBetweenMatchesWithEveryAccentStyle() {
        mount()
        for (accent in AccentStyle.entries) {
            rule.runOnIdle { style = accent }
            // The translucent idle stroke blends with a different part of the dialog gradient
            // at each row. Compare each row with its own unfocused pixel at the same position.
            focus(1)
            val firstIdle = edgeColor(0)
            val originalBounds = match(0).fetchSemanticsNode().boundsInRoot.size
            focus(0)
            val secondIdle = edgeColor(1)
            assertOutlineVisible(0, firstIdle, "$accent must visibly outline the focused result")

            match(0).performKeyInput { pressKey(Key.DirectionDown) }
            settleFocus()
            match(1).assertIsFocused()
            assertEdgeUnchanged(0, firstIdle, "$accent must clear the previous result's focus")
            assertOutlineVisible(1, secondIdle, "$accent must outline the newly focused result")
            assertEquals("Focus must not resize the result", originalBounds,
                match(0).fetchSemanticsNode().boundsInRoot.size)
            match(1).performKeyInput { pressKey(Key.DirectionCenter) }
        }
        rule.runOnIdle { assertEquals(List(AccentStyle.entries.size) { 1 }, selected) }
    }

    @Test fun handheldKeyboardArtworkChoiceKeepsItsVisibleFocusWhileSavingWithoutReapplying() {
        mount(imageKind = GameArtworkKind.COVER, handheld = true)
        focus(1)
        val idle = edgeColor(0)
        focus(0)
        assertOutlineVisible(0, idle, "The keyboard-focused artwork choice must have a visible outline")
        val focusedBeforeSaving = edgeColor(0)
        match(0).performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { enabled = false }
        settleFocus()

        match(0).assertIsFocused().assertIsNotEnabled()
        assertOutlineVisible(0, idle, "The saving result remains visibly focused")
        assertEdgeUnchanged(0, focusedBeforeSaving, "Saving must retain the focus outline's opacity")
        match(0).performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(listOf(0), selected) }
    }
}
