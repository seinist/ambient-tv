package com.ambient.tv.ui.player

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SeekBarInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun directKeySeekCommitsOnceAndSeparateTapsUseLiveTargetDespiteStaleOsdSnapshot() {
        var now = 0L
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), 100_000L) {}
        val focus = FocusRequester()
        rule.setContent {
            TvPlayerSeekBar(
                positionMs = 100_000L, // Published model deliberately never catches up.
                durationMs = 6_000_000L,
                seekState = seek,
                onSeekBy = { seek.seekBy(it, 6_000_000L) },
                onInteraction = {},
                modifier = Modifier.focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        rule.waitForIdle()
        rule.runOnIdle { focus.requestFocus() }
        fun event(action: Int, code: Int, repeat: Int = 0) {
            now += 40
            rule.activity.dispatchKeyEvent(KeyEvent(0L, now, action, code, repeat))
        }
        rule.runOnIdle {
            var expected = 100_000L
            // Native repeat events preview one target; the decoder is sought only on KeyUp.
            for (repeat in 0..4) {
                event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, repeat)
            }
            event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            expected += 5_000L
            assertEquals(expected, seek.positionMs)
            // Separate quick taps are always precise 5-second jumps, without a stale OSD base.
            repeat(20) {
                event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
                event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
                expected += 5_000L
                assertEquals(expected, seek.positionMs)
            }
            repeat(5) {
                event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
                event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
                expected -= 5_000L
                assertEquals(expected, seek.positionMs)
            }
        }
    }
}
