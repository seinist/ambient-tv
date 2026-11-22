package com.ambient.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlayerChromeSeekLifetimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Clock : PlaybackHandle {
        var position = 30_000L
        override fun status() = PlaybackStatus(positionMs = position, durationMs = 120_000L, playing = true)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun release() = Unit
    }

    private class Fixture {
        val clock = Clock()
        lateinit var chrome: PlayerChromeState
        val seeks = mutableListOf<Long>()
    }

    private fun metadata(id: String) = PlaybackMetadata(
        route = "files/$id", source = "files", mediaId = id, title = id, durationMs = 120_000L,
    )

    @Before fun reset() {
        rule.runOnUiThread {
            BackgroundPlaybackSession.stop()
            BackgroundPlaybackSession.takeCompleted()
        }
    }

    @After fun cleanup() = reset()

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            BackgroundPlaybackSession.acquire(rule.activity, metadata("first"), "fixture", Any()) { fixture.clock }
        }
        rule.setContent {
            // Gapless changes the session without setting changingItem=true.
            PersistentPlayerChrome(fixture, changingItem = false) {
                val chrome = requireNotNull(LocalPlayerChromeState.current)
                val handler: (KeyEvent) -> Boolean = { event ->
                    if (event.key != Key.DirectionRight) false else {
                        if (event.type == KeyEventType.KeyDown) {
                            chrome.osd.progressiveSeek.press(1, fixture.clock.position, 120_000L)
                        } else if (event.type == KeyEventType.KeyUp) {
                            chrome.osd.progressiveSeek.release(1)?.let(fixture.seeks::add)
                        }
                        true
                    }
                }
                SideEffect {
                    fixture.chrome = chrome
                    chrome.panelKeys.value = handler
                }
                Box(Modifier.fillMaxSize().focusRequester(chrome.engineFocus)
                    .onPreviewKeyEvent(handler).focusable())
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle { fixture.chrome.engineFocus.requestFocus() }
        return fixture
    }

    private fun key(action: Int, repeats: Int = 0) {
        val now = android.os.SystemClock.uptimeMillis()
        rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, now, action, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, repeats))
    }

    @Test fun aGaplessHandoffDiscardsTheHeldTargetBeforeTheNextComposition() {
        val fixture = mount()
        rule.runOnIdle {
            key(AndroidKeyEvent.ACTION_DOWN)
            assertEquals(35_000L, fixture.chrome.osd.progressiveSeek.previewTargetMs)
            fixture.clock.position = 5_000L
            assertTrue(BackgroundPlaybackSession.continueWithNextItem("files/first", metadata("next")))
            key(AndroidKeyEvent.ACTION_DOWN, repeats = 1)
            assertFalse("The old physical hold must not begin another scrub", fixture.chrome.osd.progressiveSeek.active)
            // Deliberately deliver KeyUp before collectAsState/recomposition can observe it.
            key(AndroidKeyEvent.ACTION_UP)
            assertTrue("The outgoing held target must never seek the next item", fixture.seeks.isEmpty())
            assertFalse(fixture.chrome.osd.progressiveSeek.active)
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle {
            key(AndroidKeyEvent.ACTION_DOWN)
            key(AndroidKeyEvent.ACTION_UP)
            assertEquals(listOf(10_000L), fixture.seeks)
        }
    }

    @Test fun ordinaryClockTicksDoNotCancelTheCurrentItemsHeldSeek() {
        val fixture = mount()
        rule.runOnIdle {
            key(AndroidKeyEvent.ACTION_DOWN)
            fixture.clock.position = 31_000L
            BackgroundPlaybackSession.tick()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(fixture.chrome.osd.progressiveSeek.active)
            key(AndroidKeyEvent.ACTION_UP)
            assertEquals(listOf(35_000L), fixture.seeks)
        }
    }
}
