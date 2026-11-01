package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionMemoryTest {
    @Test fun stopWithoutKnownPositionKeepsWhereThePlaybackWas() {
        val memory = PlaybackPositionMemory()
        memory.record(generation = 4, rendererPositionMs = 61_000, timelinePositionMs = 61_000)
        // The renderer was released in the background (30 s stop, notification Stop) and lost it.
        memory.record(generation = 4, rendererPositionMs = 0, timelinePositionMs = 0)
        assertEquals(61_000, memory.finalPosition(4, 0, 0, requestedSeek = false, ended = false, stopped = true))
    }

    @Test fun knownPositionsEndsAndProgressAreReportedUnchanged() {
        val memory = PlaybackPositionMemory()
        memory.record(4, 61_000, 61_000)
        assertEquals(75_000, memory.finalPosition(4, 75_000, 75_000, requestedSeek = false, ended = false, stopped = true))
        assertEquals(0, memory.finalPosition(4, 0, 0, requestedSeek = false, ended = true, stopped = true))
        assertEquals(0, memory.finalPosition(4, 0, 0, requestedSeek = true, ended = false, stopped = true))
        assertEquals(0, memory.finalPosition(4, 0, 0, requestedSeek = false, ended = false, stopped = false))
    }

    @Test fun positionsBelongToTheirOwnPlaybackAndIncludeTheTimelineOffset() {
        val memory = PlaybackPositionMemory(capacity = 2)
        memory.record(1, 5_000, 3_605_000)
        memory.record(2, 9_000, 9_000)
        assertEquals(3_605_000, memory.finalPosition(1, 3_600_000, 0, false, false, true))
        assertEquals(0, memory.finalPosition(3, 0, 0, false, false, true))
        memory.record(3, 1_000, 1_000)
        // Oldest playback dropped beyond capacity.
        assertEquals(0, memory.finalPosition(1, 0, 0, false, false, true))
        assertEquals(9_000, memory.finalPosition(2, 0, 0, false, false, true))
    }

    @Test fun rendererPositionIsUnknownOnlyAtZeroWithoutEndOrSeek() {
        assertFalse(rendererPositionKnown(0, requestedSeek = false, ended = false))
        assertTrue(rendererPositionKnown(1, requestedSeek = false, ended = false))
        assertTrue(rendererPositionKnown(0, requestedSeek = true, ended = false))
        assertTrue(rendererPositionKnown(0, requestedSeek = false, ended = true))
    }
}
