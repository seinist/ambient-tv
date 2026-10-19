package com.ambient.tv.spotify

import org.junit.Assert.*
import org.junit.Test

class SpotifySleepTargetTest {
    private fun state(id: String, pos: Long, dur: Long = 200_000L, playing: Boolean = true) =
        SpotifyPlaybackState(connected = true, playing = playing, positionMs = pos, durationMs = dur, current = SpotifyTrack(id, id))

    @Test fun naturalTrackEndIsTheEndOfTheTitleASkipIsAUserChange() {
        var now = 0L
        val watch = SpotifyTrackWatch { now }
        assertFalse(watch.update(state("a", 10_000)))
        assertFalse(watch.update(state("a", 198_500)))
        assertFalse(watch.update(state("b", 0)))
        assertTrue(watch.status(state("b", 0)).ended)
        assertFalse(watch.status(state("b", 0)).ended)
        assertTrue(watch.update(state("c", 0)))
        assertFalse(watch.status(state("c", 0)).ended)
    }

    @Test fun sparseUpdatesAreExtrapolated() {
        var now = 0L
        val watch = SpotifyTrackWatch { now }
        watch.update(state("a", 0))
        now = 199_000L // no report in between, playing all the time
        assertFalse(watch.update(state("b", 0)))
        assertTrue(watch.status(state("b", 0)).ended)
        now += 5_000L
        watch.update(state("b", 5_000, playing = false))
        now += 300_000L // paused: no extrapolation
        assertTrue(watch.update(state("c", 0)))
    }

    @Test fun naturalEndNeedsAKnownDuration() {
        assertFalse(spotifyTrackEndedNaturally(10_000, 0))
        assertTrue(spotifyTrackEndedNaturally(197_000, 200_000))
        assertFalse(spotifyTrackEndedNaturally(150_000, 200_000))
    }
}
