package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackSeekTest {
    private class PlayerProbe(
        durationMs: Long = 90_000L,
        positionMs: Long = 25_000L,
        live: Boolean = false,
        ended: Boolean = false,
        val failSeek: Boolean = false,
    ) {
        var parameters: SeekParameters = SeekParameters.CLOSEST_SYNC
        val operations = mutableListOf<Any>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getDuration" -> durationMs
                "getCurrentPosition" -> positionMs
                "isCurrentMediaItemLive" -> live
                "getPlaybackState" -> if (ended) Player.STATE_ENDED else Player.STATE_READY
                "getSeekParameters" -> parameters
                "setSeekParameters" -> { parameters = args!![0] as SeekParameters; operations += parameters; null }
                "seekTo" -> {
                    operations += args!!.single() as Long
                    if (failSeek) throw IllegalStateException("released player")
                    null
                }
                else -> error("Unexpected player call: ${method.name}")
            }
        } as ExoPlayer
    }

    @Test fun logicalEndUsesAnExactSafeTailAndRestoresFastSeeks() {
        val probe = PlayerProbe()
        assertTrue(probe.player.seekToPlaybackPosition(90_000))
        assertEquals(listOf(SeekParameters.EXACT, 88_500L, SeekParameters.CLOSEST_SYNC), probe.operations)
        assertSame(SeekParameters.CLOSEST_SYNC, probe.parameters)
    }

    @Test fun ordinarySeeksKeepTheirOriginalTargetAndSeekParameters() {
        val probe = PlayerProbe()
        assertTrue(probe.player.seekToPlaybackPosition(50_000))
        assertEquals(listOf(50_000L), probe.operations)
        assertSame(SeekParameters.CLOSEST_SYNC, probe.parameters)
    }

    @Test fun alreadyPlayingTheTailDoesNotSeekBackward() {
        for (positionMs in listOf(88_250L, 88_500L, 89_000L, 90_000L)) {
            val probe = PlayerProbe(positionMs = positionMs)
            assertFalse(probe.player.seekToPlaybackPosition(90_000))
            assertTrue(probe.operations.isEmpty())
        }
    }

    @Test fun liveWindowsAndUnknownDurationsKeepTheirSeekBehavior() {
        for (probe in listOf(PlayerProbe(live = true), PlayerProbe(durationMs = C.TIME_UNSET))) {
            assertTrue(probe.player.seekToPlaybackPosition(90_000))
            assertEquals(listOf(90_000L), probe.operations)
        }
    }

    @Test fun anEndedPlayerDoesNotRestartEvenWhenItsPositionWasReset() {
        val probe = PlayerProbe(positionMs = 0L, ended = true)
        assertFalse(probe.player.seekToPlaybackPosition(90_000))
        assertTrue(probe.operations.isEmpty())
    }

    @Test fun anUnsuccessfulSeekStillRestoresThePreviousParameters() {
        val probe = PlayerProbe(failSeek = true)
        val failure = runCatching { probe.player.seekToPlaybackPosition(90_000) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertSame(SeekParameters.CLOSEST_SYNC, probe.parameters)
    }
}
