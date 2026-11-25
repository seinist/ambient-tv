package com.ambient.tv.ui.common

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.tv.ui.gamepad.GameInputMode
import com.ambient.tv.ui.gamepad.GamepadConfig
import com.ambient.tv.ui.gamepad.GamepadController
import com.ambient.tv.ui.gamepad.GamepadHost
import com.ambient.tv.ui.gamepad.GamepadVolumeCommand
import com.ambient.tv.ui.input.prepareForUiKeyEvent
import com.ambient.tv.ui.keyboard.KeyAction
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Controller-generated keys must leave Android touch mode before Compose searches past a slider. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1080dp-h720dp-land-mdpi")
class ControllerPanelFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var inputMode: InputModeManager
    private lateinit var panelView: View
    private lateinit var controller: GamepadController
    private val sliderFocus = FocusRequester()
    private val choiceFocus = FocusRequester()
    private var inputTime = 1_000L
    private var value by mutableStateOf(50)
    private var filter by mutableStateOf("nearest")
    private var checked by mutableStateOf(false)
    private var choiceClicks = 0
    private var toggleChanges = 0

    private fun mount(handheld: Boolean = false, inDialog: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    if (inDialog) Dialog(onDismissRequest = {}) { Controls() }
                    else Controls()
                }
            }
        }
        rule.waitForIdle()
        controller = GamepadController(object : GamepadHost {
            private val handler = Handler(Looper.getMainLooper())
            override val playerForeground = false
            override val gameInputMode = GameInputMode.MENU
            override val recordingKey = false
            override fun now() = inputTime
            override fun emitKey(event: KeyEvent) {
                // Match the production boundary: convert the real receiving root, then
                // dispatch the controller's synthetic key without Android's ViewRoot stage.
                val root = if (inDialog) panelView.rootView else rule.activity.window.decorView
                root.prepareForUiKeyEvent(event)
                // SDK 35 requests the global transition through IWindowManager, which is a
                // no-op service in Robolectric 4.17. Mirror the resulting real root state to
                // its window session so a later popup inherits it. A broken helper still
                // leaves both in Touch mode and fails the navigation assertions below.
                InstrumentationRegistry.getInstrumentation().setInTouchMode(root.isInTouchMode)
                if (inDialog) root.dispatchKeyEvent(event) else rule.activity.dispatchKeyEvent(event)
            }
            override fun perform(action: KeyAction) = error("Unexpected action: $action")
            override fun volume(command: GamepadVolumeCommand) = error("Unexpected volume: $command")
            override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delayMs)
                return { handler.removeCallbacks(runnable) }
            }
        }, config = { GamepadConfig() }, onButton = {}, rawSink = { null }, capture = { null })
        enterTouchMode()
        rule.runOnIdle {
            assertFalse("A clickable choice must initially obey touch mode", choiceFocus.requestFocus())
            assertTrue(sliderFocus.requestFocus())
        }
        rule.onNodeWithTag("value").assertIsFocused()
    }

    @Composable
    private fun Controls() {
        inputMode = LocalInputModeManager.current
        panelView = LocalView.current
        Column(Modifier.width(340.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerValueSlider("Strength", value, 0..100,
                modifier = Modifier.testTag("value").focusRequester(sliderFocus), onValueChange = { value = it })
            RightPanelChoice("Picture", false, { choiceClicks++ },
                modifier = Modifier.fillMaxWidth().testTag("choice").focusRequester(choiceFocus))
            RightPanelChoice("Unavailable", false, { error("Disabled choice activated") },
                modifier = Modifier.fillMaxWidth().testTag("disabled"), enabled = false)
            PlayerDropUpSelector("Filter", if (filter == "nearest") "Nearest" else "Smooth",
                listOf("nearest" to "Nearest", "smooth" to "Smooth"), selectedKey = filter,
                onSelected = { filter = it })
            TvSettingRow("Interpolation", checked = checked, onCheckedChange = {
                checked = it
                toggleChanges++
            }, modifier = Modifier.testTag("toggle"))
        }
    }

    private fun enterTouchMode() {
        rule.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            // Robolectric updates the window session but does not notify an already attached
            // root. Apply Android's real local transition, including Compose's mode listener.
            val root = ReflectionHelpers.callInstanceMethod<Any>(panelView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Boolean>(root, "ensureTouchModeLocally",
                ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(panelView.isInTouchMode)
            assertEquals(InputMode.Touch, inputMode.inputMode)
        }
    }

    private fun hat(x: Float = 0f, y: Float = 0f) {
        rule.runOnUiThread {
            fun motion(hatX: Float, hatY: Float): MotionEvent {
                val pointers = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
                val coords = arrayOf(MotionEvent.PointerCoords().apply {
                    setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
                    setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
                })
                return MotionEvent.obtain(inputTime, inputTime++, MotionEvent.ACTION_MOVE, 1,
                    pointers, coords, 0, 0, 1f, 1f, 7, 0, InputDevice.SOURCE_JOYSTICK, 0)
            }
            val press = motion(x, y)
            val release = motion(0f, 0f)
            try {
                assertTrue(controller.handleMotion(press))
                assertTrue(controller.handleMotion(release))
            } finally {
                press.recycle()
                release.recycle()
            }
        }
        rule.waitForIdle()
    }

    private fun confirm() {
        rule.runOnUiThread {
            val downTime = inputTime++
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(controller.handleKey(KeyEvent(downTime, inputTime++, action,
                    KeyEvent.KEYCODE_BUTTON_A, 0, 0, 7, 0, 0, InputDevice.SOURCE_GAMEPAD)))
            }
        }
        rule.waitForIdle()
    }

    private fun assertMixedRowsAreReachable() {
        hat(y = 1f)
        rule.runOnIdle {
            assertFalse(panelView.isInTouchMode)
            assertEquals(InputMode.Keyboard, inputMode.inputMode)
        }
        rule.onNodeWithTag("choice").assertIsFocused()
        confirm()
        rule.runOnIdle { assertEquals(1, choiceClicks) }
        // Disabled entries remain skipped; fixing input mode must not enable them.
        hat(y = 1f)
        rule.onNodeWithText("Filter").assertIsFocused()
        hat(y = 1f)
        rule.onNodeWithTag("toggle").assertIsFocused()
        confirm()
        rule.runOnIdle {
            assertTrue(checked)
            assertEquals(1, toggleChanges)
        }
    }

    private fun focusedPopupOption(text: String): SemanticsNodeInteraction {
        // The trigger also contains the currently selected option's text. Select the actual
        // popup row, and allow its bounded withFrameNanos focus request to finish.
        val option = hasText(text) and hasAnyAncestor(isPopup())
        rule.waitUntil(timeoutMillis = 2_000L) {
            rule.onAllNodes(option and isFocused()).fetchSemanticsNodes().size == 1
        }
        return rule.onNode(option).assertIsFocused()
    }

    @Test fun controllerHatLeavesTouchModeAndNavigatesAllTvPanelControlTypes() {
        mount()
        assertMixedRowsAreReachable()
        hat(y = -1f)
        rule.onNodeWithText("Filter").assertIsFocused()
        confirm()
        // The anchored list owns a separate Compose window. Send its subsequent keys to
        // that focused window; the original controller transition must already permit focus.
        focusedPopupOption("Nearest").performKeyInput { pressKey(Key.DirectionDown) }
        focusedPopupOption("Smooth").performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("smooth", filter) }
        rule.onNodeWithText("Filter").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun handheldControlsAllowTouchAndControllerToAlternateWithoutDoubleActivation() {
        mount(handheld = true)
        assertMixedRowsAreReachable()
        enterTouchMode()
        rule.onNodeWithTag("toggle").performTouchInput { click() }
        rule.runOnIdle {
            assertFalse(checked)
            assertEquals(2, toggleChanges)
            assertEquals(InputMode.Touch, inputMode.inputMode)
            assertTrue(sliderFocus.requestFocus())
        }
        hat(y = 1f)
        rule.onNodeWithTag("choice").assertIsFocused()
        confirm()
        rule.runOnIdle {
            assertEquals(InputMode.Keyboard, inputMode.inputMode)
            assertEquals(2, choiceClicks)
        }
    }

    @Test fun aDialogReceivingRootAlsoLeavesTouchModeForControllerNavigation() {
        mount(inDialog = true)
        assertMixedRowsAreReachable()
    }

    @After fun releaseController() {
        if (::controller.isInitialized) rule.runOnUiThread { controller.releaseAll() }
    }
}
