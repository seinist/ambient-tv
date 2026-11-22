package com.ambient.tv.ui.player

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class SeekBarTimelineTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture {
        val item = mutableStateOf("first-video")
        val position = mutableLongStateOf(30_000L)
        val focus = FocusRequester()
        val seeks = mutableListOf<Long>()
        var visualPosition = 0L
    }

    private fun mount(handheld: Boolean = false): Fixture {
        val fixture = Fixture()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        TvPlayerSeekBar(
                            positionMs = fixture.position.longValue,
                            // Next Up can replace a video without passing through duration=0.
                            durationMs = 120_000L,
                            timelineKey = fixture.item.value,
                            // Deliberately retain the pre-seek engine report until the test
                            // acknowledges it, as a starting/buffering renderer can do.
                            onSeekBy = { fixture.seeks += it },
                            onInteraction = {},
                            showEndTime = false,
                            timelineContext = { position ->
                                SideEffect { fixture.visualPosition = position }
                            },
                            modifier = Modifier.testTag("timeline").focusRequester(fixture.focus),
                        )
                    }
                }
            }
        }
        advance(300L)
        return fixture
    }

    private fun advance(milliseconds: Long = 48L) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun tapMiddle() {
        rule.onNodeWithTag("timeline").performTouchInput {
            click(Offset(width * 0.5f, 12f))
        }
        advance()
    }

    private fun assertNextUpReadout(handheld: Boolean) {
        val fixture = mount(handheld)
        rule.onNodeWithText("0:30 / 2:00").assertExists()
        tapMiddle()
        // A committed target must move the text with the seekbar even before the engine ack.
        rule.onNodeWithText("1:00 / 2:00").assertExists()
        rule.runOnIdle {
            assertEquals(listOf(30_000L), fixture.seeks)
            assertEquals(60_000L, fixture.visualPosition)
            fixture.item.value = "next-video"
            fixture.position.longValue = 5_000L
        }
        advance()
        rule.onNodeWithText("0:05 / 2:00").assertExists()
        rule.onNodeWithText("−1:55").assertExists()
        rule.runOnIdle { assertEquals(5_000L, fixture.visualPosition) }

        tapMiddle()
        rule.onNodeWithText("1:00 / 2:00").assertExists()
        rule.runOnIdle {
            assertEquals(listOf(30_000L, 55_000L), fixture.seeks)
            fixture.position.longValue = 60_000L
        }
        advance()
        rule.runOnIdle { fixture.position.longValue = 61_000L }
        advance()
        rule.onNodeWithText("1:01 / 2:00").assertExists()
        rule.onNodeWithText("−0:59").assertExists()
        rule.runOnIdle { assertEquals(61_000L, fixture.visualPosition) }
    }

    @Test fun televisionElapsedTimeFollowsCommittedSeeksAcrossNextUp() {
        assertNextUpReadout(handheld = false)
    }

    @Test fun handheldElapsedTimeFollowsCommittedSeeksAcrossNextUp() {
        assertNextUpReadout(handheld = true)
    }

    @Test fun heldRemotePreviewKeepsTheLiveClockUntilRelease() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.focus.requestFocus()
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        advance()
        rule.onNodeWithText("0:30 / 2:00").assertExists()
        rule.runOnIdle {
            assertTrue(fixture.seeks.isEmpty())
            assertEquals(35_000L, fixture.visualPosition)
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        advance()
        rule.onNodeWithText("0:35 / 2:00").assertExists()
        rule.runOnIdle { assertEquals(listOf(5_000L), fixture.seeks) }
    }

    @Test fun anUnacknowledgedSeekStillFallsBackToTheDecoderClock() {
        val fixture = mount()
        tapMiddle()
        rule.onNodeWithText("1:00 / 2:00").assertExists()
        advance(SEEK_VISUAL_ACK_TIMEOUT_MS + 100L)
        rule.onNodeWithText("0:30 / 2:00").assertExists()
        rule.runOnIdle { assertEquals(30_000L, fixture.visualPosition) }
    }

    @Test fun nextUpDiscardsAHeldPreviewBeforeItsKeyIsReleased() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.focus.requestFocus()
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        advance()
        rule.runOnIdle {
            assertEquals(35_000L, fixture.visualPosition)
            fixture.item.value = "next-video"
            fixture.position.longValue = 5_000L
        }
        advance()
        rule.onNodeWithText("0:05 / 2:00").assertExists()
        rule.runOnIdle {
            assertEquals(5_000L, fixture.visualPosition)
            rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue("The outgoing video's held target must never seek the next video", fixture.seeks.isEmpty())
        }
    }
}
