package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class Media3LiveStartPolicyTest {
    @Test fun runningHlsStartsFromManifestLiveEdgeWithoutExplicitSeek() {
        assertNull(media3InitialSeekPositionMs(0L, liveStream = true, timeshift = false))
        // Old absolute clocks from a prior session must not override the live edge.
        assertNull(media3InitialSeekPositionMs(2_500_000L, liveStream = true, timeshift = false))
    }

    @Test fun timeshiftKeepsItsStableAbsoluteSeekAndVodRetainsResume() {
        assertEquals(0L, media3InitialSeekPositionMs(0L, liveStream = true, timeshift = true))
        assertEquals(30_000L, media3InitialSeekPositionMs(30_000L, liveStream = true, timeshift = true))
        assertEquals(120_000L, media3InitialSeekPositionMs(120_000L, liveStream = false, timeshift = false))
        assertEquals(0L, media3InitialSeekPositionMs(-1L, liveStream = false, timeshift = false))
    }
}
