package com.ambient.tv.ui.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.gamepad.ControllerModel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Runs the actual resource-backed renderer, including touch and TV keyboard activation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputDeviceArtworkUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var selected: String? by mutableStateOf(null)

    private fun render(artwork: InputArtwork, handheld: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    Box(Modifier.width(320.dp).background(androidx.tv.material3.MaterialTheme.colorScheme.background)
                        .testTag("input-artwork-preview")) {
                        InputDeviceArtwork(artwork, selected, { selected = it.id })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun phoneVectorKeepsItsRatioAndTappingItsRealButtonSelectsThatButton() {
        val art = attachedArtwork("controller", controllerModel = ControllerModel.PS5)
        render(art, handheld = true)
        val image = rule.onNodeWithTag("input-artwork-image")
        val bounds = image.fetchSemanticsNode().boundsInRoot
        assertEquals(art.aspectRatio, bounds.width / bounds.height, .01f)
        val a = art.keys.single { it.id == "a" }
        image.performTouchInput { click(Offset(a.x * width, a.y * height)) }
        rule.runOnIdle { assertEquals("a", selected) }
        savePreview("phone-dualsense")
    }

    @Test fun denseKeyboardOffersFullHeightAccessibleTouchButtons() {
        render(attachedArtwork("keyboard"), handheld = true)
        rule.onNodeWithTag("input-artwork-key-strip")
            .performScrollToNode(hasTestTag("input-artwork-select-Space"))
        val space = rule.onNodeWithTag("input-artwork-select-Space")
        space.assertHasClickAction().assertIsDisplayed()
        val bounds = space.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= 48f)
        space.performClick()
        rule.runOnIdle { assertEquals("Space", selected) }
        space.assertIsSelected()
        savePreview("phone-keyboard")
    }

    @Test fun tvKeepsDirectFocusableButtonSelection() {
        render(attachedArtwork("controller", controllerModel = ControllerModel.XBOX_ONE), handheld = false)
        rule.onNodeWithTag("input-artwork-key-strip").assertDoesNotExist()
        val key = rule.onNodeWithTag("input-artwork-key-a")
        key.assertHasClickAction()
        key.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key.assertIsFocused()
        key.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals("a", selected) }
        key.assertIsSelected()
        savePreview("tv-xbox-one")
    }

    @Test fun narrowRemoteKeepsSeparateColorButtonsAndSelectsTheTappedColor() {
        val art = attachedArtwork("remote")
        render(art, handheld = true)
        val image = rule.onNodeWithTag("input-artwork-image")
        val bounds = image.fetchSemanticsNode().boundsInRoot
        assertEquals(art.aspectRatio, bounds.width / bounds.height, .01f)
        val green = art.keys.single { it.id == "green" }
        image.performTouchInput { click(Offset(green.x * width, green.y * height)) }
        rule.runOnIdle { assertEquals("green", selected) }
        savePreview("phone-remote")
    }

    private fun savePreview(name: String) {
        val bitmap = rule.onNodeWithTag("input-artwork-preview").captureToImage().asAndroidBitmap()
        val file = File("build/reports/input-artwork/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
