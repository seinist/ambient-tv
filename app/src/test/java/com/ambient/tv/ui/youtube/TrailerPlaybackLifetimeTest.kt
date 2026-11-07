package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.PlaybackSeekStopTest
import com.ambient.tv.ui.player.PlaybackStatus
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class TrailerPlaybackLifetimeTest {
    private class Handle : PlaybackHandle {
        var released = false
        override fun status() = PlaybackStatus(positionMs = 10_000, durationMs = 120_000, playing = true)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() { released = true }
    }

    private fun acquire(id: String) = BackgroundPlaybackSession.acquire(
        RuntimeEnvironment.getApplication(),
        PlaybackMetadata(Destinations.trailerPlayer(id), id, source = "youtube", mediaId = id, isTrailer = true),
        "fixture", Any(),
    ) { Handle() }

    @After fun cleanup() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    @Test fun exitingAfterNextResolvesButBeforeDecoderAcquisitionStopsTheOldTrailer() {
        val lifetime = TrailerPlaybackLifetime()
        val old = acquire("a")
        lifetime.loaded("a")
        val transition = BackgroundPlaybackSession.beginItemTransition()
        lifetime.replacing(transition)
        // Resolution publishes B while the service still owns A's decoder and handoff.
        lifetime.loaded("b")
        assertFalse(old.released)
        assertTrue(BackgroundPlaybackSession.isSourceReplacement(transition))
        lifetime.stopIfOwned()
        assertTrue(old.released)
        assertFalse(BackgroundPlaybackSession.snapshot.value.active)
    }

    @Test fun successfulNextSurvivesResolutionAndIsStoppedOnlyOnExit() {
        val lifetime = TrailerPlaybackLifetime()
        val old = acquire("a")
        lifetime.loaded("a")
        lifetime.replacing(BackgroundPlaybackSession.beginItemTransition())
        lifetime.loaded("b")
        val next = acquire("b")
        assertTrue(old.released)
        assertFalse(next.released)
        assertTrue(BackgroundPlaybackSession.snapshot.value.active)
        lifetime.stopIfOwned()
        assertTrue(next.released)
        assertFalse(BackgroundPlaybackSession.snapshot.value.active)
    }

    @Test fun outgoingDestinationCannotStopAnotherReplacementOrItsDecoder() {
        val lifetime = TrailerPlaybackLifetime()
        acquire("a")
        lifetime.loaded("a")
        lifetime.replacing(BackgroundPlaybackSession.beginItemTransition())
        val otherTransition = BackgroundPlaybackSession.beginItemTransition()
        lifetime.stopIfOwned()
        assertTrue(BackgroundPlaybackSession.isSourceReplacement(otherTransition))

        val other = acquire("other")
        lifetime.stopIfOwned()
        assertFalse(other.released)
        assertTrue(BackgroundPlaybackSession.snapshot.value.active)
    }
}
