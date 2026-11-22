package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ambient.tv.R
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-port-mdpi")
class GameStateActionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val entry = GameStateEntry("m1", GameStateKind.MANUAL, 1L, "core", "build")

    private fun showDialog(onLoad: () -> Unit, onDelete: () -> Unit = {}, onRename: (String) -> Unit = {},
        loadAvailable: Boolean = true) {
        rule.setContent {
            // Phone dimensions alone do not select the app's touch controls or dialog layout.
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    var open by remember { mutableStateOf(true) }
                    if (open) GameStateActionsDialog(entry, "State 1",
                        onDismiss = { open = false },
                        onLoad = { open = false; onLoad() },
                        onDelete = { open = false; onDelete() },
                        onRename = { open = false; onRename(it) }, loadAvailable = loadAvailable)
                }
            }
        }
    }

    @Test fun phoneHasExplicitLoadRenameAndDeleteWithDeletionConfirmation() {
        var loads = 0
        var deletes = 0
        showDialog(onLoad = { loads++ }, onDelete = { deletes++ })
        val load = rule.activity.getString(R.string.game_state_load)
        rule.onNodeWithText(load).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_rename)).assertIsDisplayed()
        val delete = rule.activity.getString(R.string.saved_profiles_delete)
        rule.onNodeWithText(delete).assertIsDisplayed().performTouchInput { click() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, deletes); assertEquals(0, loads) }
        val confirmation = rule.activity.getString(R.string.game_state_delete_title, "State 1")
        rule.onNodeWithText(confirmation).assertIsDisplayed()
        rule.onNodeWithText(load).assertDoesNotExist()
        rule.onNodeWithText(delete).assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, deletes); assertEquals(0, loads) }
        rule.onNodeWithText(confirmation).assertDoesNotExist()
    }

    @Test fun manualLabelIsEditableWithoutLoadingTheGame() {
        var label: String? = null
        var loads = 0
        showDialog(onLoad = { loads++ }, onRename = { label = it })
        val rename = rule.activity.getString(R.string.game_state_rename)
        rule.onNodeWithText(rename).assertIsDisplayed().performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText(rename).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_load)).assertDoesNotExist()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        // TvTextField intentionally mounts its editor only after the user taps the name field.
        val name = hasText(rule.activity.getString(R.string.game_state_name))
        rule.onNode((name or hasAnyDescendant(name)) and hasClickAction())
            .assertIsDisplayed().performTouchInput { click() }
        rule.onNode(hasSetTextAction()).assertIsDisplayed().performTextInput("Checkpoint")
        rule.onNodeWithText(rule.activity.getString(R.string.files_save)).assertIsDisplayed()
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals("Checkpoint", label); assertEquals(0, loads) }
        rule.onNodeWithText(rename).assertDoesNotExist()
    }

    @Test fun unavailableLoadIsAbsentWhileRenameAndDeleteRemainUsable() {
        var loads = 0
        var deletes = 0
        showDialog(onLoad = { loads++ }, onDelete = { deletes++ }, loadAvailable = false)
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_load)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_rename)).assertIsDisplayed()
        val delete = rule.activity.getString(R.string.saved_profiles_delete)
        rule.onNodeWithText(delete).assertIsDisplayed().performTouchInput { click() }
        rule.onNodeWithText(delete).assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(0, loads); assertEquals(1, deletes) }
    }

    @Test fun changedSessionRestrictionRemovesLoadFromAnAlreadyOpenDialog() {
        var loadAvailable by mutableStateOf(true)
        var loads = 0
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    GameStateActionsDialog(entry, "State 1", onDismiss = {}, onLoad = { loads++ },
                        onDelete = {}, onRename = {}, loadAvailable = loadAvailable)
                }
            }
        }
        val load = rule.activity.getString(R.string.game_state_load)
        rule.onNodeWithText(load).assertIsDisplayed()
        rule.runOnIdle { loadAvailable = false }
        rule.onNodeWithText(load).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_rename)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, loads) }
    }

    @Test fun stripReportsDialogUntilNestedEditorClosesAndClearsItWhenDisposed() {
        var mounted by mutableStateOf(true)
        val visibility = mutableListOf<Boolean>()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    if (mounted) GameStateStrip(open = true, states = listOf(entry), coreStamp = "build",
                        title = { "State 1" }, screenshot = { null }, onLoad = {}, onDelete = {},
                        onRename = { _, _ -> }, onClose = {}, canLoad = { false },
                        onDialogVisibilityChanged = { visibility += it })
                }
            }
        }
        rule.onNodeWithText("State 1").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(true), visibility) }
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_rename)).performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(true), visibility) }
        rule.onNodeWithText(rule.activity.getString(R.string.files_cancel)).performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(true), visibility) }
        rule.runOnIdle { mounted = false }
        rule.runOnIdle { assertEquals(listOf(true, false), visibility) }
    }

    @Test fun becomingBusyDismissesStateDialogAndCompletionDoesNotReopenIt() {
        var enabled by mutableStateOf(true)
        var dialog = false
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    GameStateStrip(open = true, states = listOf(entry), coreStamp = "build",
                        title = { "State 1" }, screenshot = { null }, onLoad = {}, onDelete = {},
                        onRename = { _, _ -> }, onClose = {}, canLoad = { true }, enabled = enabled,
                        onDialogVisibilityChanged = { dialog = it })
                }
            }
        }
        rule.onNodeWithText("State 1").performTouchInput { click() }
        rule.runOnIdle { assertTrue(dialog); enabled = false }
        rule.runOnIdle { assertFalse(dialog); enabled = true }
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_load)).assertDoesNotExist()
        rule.runOnIdle { assertFalse(dialog) }
    }
}
