package com.ambient.tv.ui.player

import androidx.compose.ui.input.key.Key
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlayerOsdSeekIntentTest {
    @After fun cleanup() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    @Test fun aDirectScrubPublishesUserIntentAtKeyDownButSeeksOnlyOnKeyUp() {
        val osd = PlayerOsdController()
        val seeks = mutableListOf<Long>()
        val target = object : OsdKeyTarget {
            override fun isPlaying() = true
            override fun togglePlayback() = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun stop() = Unit
            override fun seek(deltaMs: Long, revealOsd: Boolean) { seeks += deltaMs }
            override fun showOsd() = Unit
            override fun hideSeekFeedback() = Unit
            override fun restart() = Unit
            override fun next() = Unit
            override fun previousTrack() = Unit
            override fun zap(delta: Int) = Unit
        }
        val steps = progressiveOsdSeekSteps(30_000L, 10_000L, 120_000L, osd)
        val hidden = OsdKeyState(OsdContent.VIDEO, osdVisible = false, panelOpen = false,
            seekFeedbackVisible = false)
        val before = BackgroundPlaybackSession.externalSeekVersion

        // Ordinary focus navigation is not a scrub and cannot invalidate a pending resume.
        assertFalse(handlePlayerOsdKey(Key.DirectionRight, true, hidden.copy(osdVisible = true), steps, target))
        assertEquals(before, BackgroundPlaybackSession.externalSeekVersion)
        assertTrue(handlePlayerOsdKey(Key.DirectionRight, true, hidden, steps, target))
        assertEquals(before + 1, BackgroundPlaybackSession.externalSeekVersion)
        assertTrue(osd.progressiveSeek.active)
        assertTrue(osd.seekFeedback)
        assertTrue("KeyDown must not issue a decoder command", seeks.isEmpty())

        // Native repeats extend the same preview; only a genuinely new start publishes intent.
        assertTrue(handlePlayerOsdKey(Key.DirectionRight, true, hidden, steps, target))
        assertEquals(before + 1, BackgroundPlaybackSession.externalSeekVersion)
        assertTrue(seeks.isEmpty())
        assertTrue(handlePlayerOsdKey(Key.DirectionRight, false, hidden, steps, target))
        assertEquals(listOf(5_000L), seeks)
        assertFalse(osd.progressiveSeek.active)
    }
}
