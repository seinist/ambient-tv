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
            inputModeManager = LocalInputModeManager.current
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme(accentStyleKey = style.key, accentMotionKey = "off") {
                    AppDialog(onDismiss = {}, title = "Choose game", scrollable = false) {
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

    @Test fun visibleFocusFollowsDpadBetweenMatchesWithEveryAccentStyle() {
        mount()
        for (accent in AccentStyle.entries) {
            rule.runOnIdle { style = accent }
            focus(0)
            val originalBounds = match(0).fetchSemanticsNode().boundsInRoot.size
            val idle = edgeColor(1)
            assertTrue("$accent must visibly outline the focused result", distance(edgeColor(0), idle) > .15f)

            match(0).performKeyInput { pressKey(Key.DirectionDown) }
            settleFocus()
            match(1).assertIsFocused()
            assertTrue("$accent must clear the previous result's focus", distance(edgeColor(0), idle) < .04f)
            assertTrue("$accent must outline the newly focused result", distance(edgeColor(1), idle) > .15f)
            assertEquals("Focus must not resize the result", originalBounds,
                match(0).fetchSemanticsNode().boundsInRoot.size)
            match(1).performKeyInput { pressKey(Key.DirectionCenter) }
        }
        rule.runOnIdle { assertEquals(List(AccentStyle.entries.size) { 1 }, selected) }
    }

    @Test fun handheldKeyboardArtworkChoiceKeepsItsVisibleFocusWhileSavingWithoutReapplying() {
        mount(imageKind = GameArtworkKind.COVER, handheld = true)
        focus(0)
        assertTrue(distance(edgeColor(0), edgeColor(1)) > .15f)
        match(0).performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { enabled = false }
        settleFocus()

        match(0).assertIsFocused().assertIsNotEnabled()
        assertTrue("The saving result remains visibly focused", distance(edgeColor(0), edgeColor(1)) > .15f)
        match(0).performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(listOf(0), selected) }
    }
}
