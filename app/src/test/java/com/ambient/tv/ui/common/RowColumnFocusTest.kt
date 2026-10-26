package com.ambient.tv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.sound.UiSound
import com.ambient.tv.ui.sound.UiSoundFeedback
import com.ambient.tv.ui.sound.uiSoundOnFocus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Up/Down between rows: nearest column from live coordinates, exactly one focus tick per move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RowColumnFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Recorder : UiSoundFeedback {
        val sounds = mutableListOf<UiSound>()
        override fun play(kind: UiSound) { sounds += kind }
        override fun perform(kind: UiSound, action: () -> Unit) { action(); play(kind) }
    }

    private val recorder = Recorder()
    private val start = FocusRequester()
    private val lowerScroll = ScrollState(0)

    /** Row "b" tiles either play their own focus sound (like MediaTile) or stay silent. */
    private fun setUp(lowerTilesSound: Boolean) {
        rule.setContent {
            CompositionLocalProvider(LocalUiSoundPlayer provides recorder) {
                val focus = androidx.compose.runtime.remember { RowColumnFocus() }
                Column(Modifier.rowColumnFocusNavigation(focus) { listOf("a", "b") }) {
                    Row {
                        repeat(3) { i ->
                            Box(Modifier.size(100.dp).testTag("a:$i")
                                .rowColumnFocusTarget(focus, "a", i)
                                .then(if (i == 1) Modifier.focusRequester(start) else Modifier)
                                .focusable())
                        }
                    }
                    Row(Modifier.width(300.dp).horizontalScroll(lowerScroll)) {
                        repeat(4) { i ->
                            Box(Modifier.size(100.dp).testTag("b:$i")
                                .rowColumnFocusTarget(focus, "b", i, soundsOnFocus = lowerTilesSound)
                                .then(if (lowerTilesSound) Modifier.uiSoundOnFocus(recorder) else Modifier)
                                .focusable())
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { start.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag("a:1").assertIsFocused()
        recorder.sounds.clear()
    }

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    @Test fun selfSoundingTileGetsOneTickNotTwo() {
        setUp(lowerTilesSound = true)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("b:1").assertIsFocused()
        assertEquals(listOf(UiSound.FOCUS), recorder.sounds)
    }

    @Test fun silentTileStillGetsTheNavigationTick() {
        setUp(lowerTilesSound = false)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("b:1").assertIsFocused()
        assertEquals(listOf(UiSound.FOCUS), recorder.sounds)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        rule.onNodeWithTag("a:1").assertIsFocused()
        assertEquals(listOf(UiSound.FOCUS, UiSound.FOCUS), recorder.sounds)
    }

    /** Coordinates captured at placement stay live: a row scrolled afterwards still maps to its current column. */
    @Test fun scrolledRowUsesCurrentTilePositions() {
        setUp(lowerTilesSound = false)
        val px = with(rule.density) { 100.dp.roundToPx() }
        rule.runOnIdle { runBlocking { lowerScroll.scrollTo(px) } }
        rule.waitForIdle()
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("b:2").assertIsFocused()
    }
}
