package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ambient.tv.R
import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import org.junit.Assert.assertEquals
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

    @Test fun phoneHasExplicitLoadRenameAndDeleteWithDeletionConfirmation() {
        var loads = 0
        var deletes = 0
        rule.setContent { GameStateActionsDialog(entry, "State 1", {}, { loads++ }, { deletes++ }, {}) }
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_load)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_rename)).assertIsDisplayed()
        val delete = rule.activity.getString(R.string.saved_profiles_delete)
        rule.onNodeWithText(delete).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, deletes); assertEquals(0, loads) }
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_delete_title, "State 1")).assertIsDisplayed()
        rule.onNodeWithText(delete).performClick()
        rule.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun manualLabelIsEditableWithoutLoadingTheGame() {
        var label: String? = null
        var loads = 0
        rule.setContent { GameStateActionsDialog(entry, "State 1", {}, { loads++ }, {}, { label = it }) }
        rule.onNodeWithText(rule.activity.getString(R.string.game_state_rename)).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("Checkpoint")
        rule.onNodeWithText(rule.activity.getString(R.string.files_save)).performClick()
        rule.runOnIdle { assertEquals("Checkpoint", label); assertEquals(0, loads) }
    }
}
