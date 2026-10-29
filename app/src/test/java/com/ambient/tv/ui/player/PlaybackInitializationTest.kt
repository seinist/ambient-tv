package com.ambient.tv.ui.player

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PlaybackInitializationTest {
    private class Resource {
        var releases = 0
        fun release() { releases++ }
    }

    @Test fun successfulInitializationKeepsItsResourcesAndDoesNotRepeatTheStart() {
        val audio = Resource()
        val player = Resource()
        var starts = 0
        val result = initializePlaybackOrRelease(audio::release, player::release) {
            starts++
            player
        }

        assertSame(player, result)
        assertEquals(1, starts)
        assertEquals(0, audio.releases)
        assertEquals(0, player.releases)
    }

    @Test fun failureBeforePlayerCreationStillClosesTheAudioSession() {
        val audio = Resource()
        var player: Resource? = null
        val failure = IllegalStateException("Cannot build media source")
        val thrown = runCatching {
            initializePlaybackOrRelease(audio::release, { player?.release() }) { throw failure }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(1, audio.releases)
        assertNull(player)
    }

    @Test fun failureAfterPlayerCreationClosesRegistrationsThenThePlayerOnce() {
        val released = mutableListOf<String>()
        var player: Resource? = null
        val failure = IllegalStateException("Unsupported media source")
        var starts = 0
        val thrown = runCatching {
            initializePlaybackOrRelease(
                { released += "primer" },
                { released += "image" },
                { released += "audio" },
                { released += "spectrum" },
                { player?.release(); released += "player" },
            ) {
                starts++
                player = Resource()
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("primer", "image", "audio", "spectrum", "player"), released)
        assertEquals(1, player?.releases)
        assertEquals(1, starts)
    }

    @Test fun failingCleanupCannotSkipTheDecoderOrReplaceTheInitializationError() {
        val player = Resource()
        val failure = IllegalArgumentException("Invalid media source")
        val cleanupFailure = IllegalStateException("Cannot unregister listener")
        val thrown = runCatching {
            initializePlaybackOrRelease({ throw cleanupFailure }, player::release) { throw failure }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertEquals(1, player.releases)
    }

    @Test fun cancellationAndFatalErrorsAreCleanedUpAndRethrownUnchanged() {
        for (failure in listOf(CancellationException("Cancelled"), LinkageError("Native loader failed"))) {
            val player = Resource()
            val thrown = runCatching {
                initializePlaybackOrRelease(player::release) { throw failure }
            }.exceptionOrNull()

            assertSame(failure, thrown)
            assertEquals(1, player.releases)
        }
    }

    @Test fun cleanupRethrowingTheSameErrorCannotHideItWithSelfSuppression() {
        val player = Resource()
        val failure = IllegalStateException("Start failed")
        val thrown = runCatching {
            initializePlaybackOrRelease({ throw failure }, player::release) { throw failure }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(1, player.releases)
    }
}
