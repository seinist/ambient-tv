package com.ambient.tv.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import com.ambient.tv.util.createAndroidComposeRule
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Cancellation belongs to the selection, including phases with no native dialog window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class GameSelectionDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Selection(phase: GameSelectionPhase) {
        var state by mutableStateOf<GameSelectionUiState?>(GameSelectionUiState(phase))
        var cancelled = 0
        var pageBacks = 0
        fun cancel() { cancelled++; state = null }
    }

    private fun mount(phase: GameSelectionPhase): Selection {
        val selection = Selection(phase)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            // The library uses Back to focus the sidebar without changing its navigation entry.
            BackHandler { selection.pageBacks++ }
            AmbientTvTheme {
                GameSelectionDialog(selection.state, onDismiss = selection::cancel,
                    onPlay = {}, onWake = {}, onDetails = {})
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        return selection
    }

    private fun assertInvisiblePhaseCancelsBeforePageBack(phase: GameSelectionPhase) {
        val selection = mount(phase)
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(rule.mainClock.currentTime < 180L)
        rule.runOnIdle {
            rule.activity.onBackPressedDispatcher.onBackPressed()
            assertNull(selection.state)
            assertEquals(1, selection.cancelled)
            assertEquals(0, selection.pageBacks)
        }
        // runOnIdle synchronizes before the callback. Its state write still needs snapshot
        // notification and a new frame; LAUNCHING has no timer to drive that frame for us.
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500L)
        rule.waitForIdle()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.runOnIdle {
            assertNull(selection.state)
            assertEquals(1, selection.cancelled)
            assertEquals(0, selection.pageBacks)
            // Only a new Back after cancellation may reach the originating page.
            rule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, selection.pageBacks)
            assertEquals(1, selection.cancelled)
        }
    }

    @Test fun backCancelsResolvingBeforeDelayedProgressIsVisible() {
        assertInvisiblePhaseCancelsBeforePageBack(GameSelectionPhase.RESOLVING)
    }

    @Test fun backCancelsCheckingBeforeDelayedProgressIsVisible() {
        assertInvisiblePhaseCancelsBeforePageBack(GameSelectionPhase.CHECKING)
    }

    @Test fun backCancelsLaunchingBeforeTargetHandoffCompletes() {
        assertInvisiblePhaseCancelsBeforePageBack(GameSelectionPhase.LAUNCHING)
    }

    @Test fun visibleDialogOwnsBackAndDismissesSelectionOnce() {
        val selection = mount(GameSelectionPhase.OPTIONS)
        rule.mainClock.advanceTimeBy(500L)
        rule.waitForIdle()
        rule.onAllNodes(isDialog()).assertCountEquals(1)
        rule.runOnIdle {
            // Compose Dialog has its own ComponentDialog/dispatcher, separate from the activity.
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
            assertNull(selection.state)
            assertEquals(1, selection.cancelled)
            assertEquals(0, selection.pageBacks)
        }
        // Resume normal frame processing after the window's callback changes selection.state.
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500L)
        rule.waitForIdle()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.runOnIdle {
            assertNull(selection.state)
            assertEquals(1, selection.cancelled)
            assertEquals(0, selection.pageBacks)
        }
    }
}
