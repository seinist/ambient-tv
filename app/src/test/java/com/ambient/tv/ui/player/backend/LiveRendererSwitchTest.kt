package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.player.PREVIOUS_PLAYER_HINT_DELAY_MS
import com.ambient.tv.ui.player.media3JoinsReleaseGate
import com.ambient.tv.ui.player.previousPlayerHintVisible
import org.junit.Assert.*
import org.junit.Test

class LiveRendererSwitchTest {
    @Test fun watchdogDoesNotCountWhileWaitingForThePreviousPlayer() {
        // A renderer switch can wait far longer than 5 s for the old live stream to close.
        assertFalse(mpvBackendUnresponsive(nowMs = 60_000L, lastProgressMs = 0L, ownerStarted = false))
    }

    @Test fun watchdogCountsFromEngineStart() {
        assertFalse(mpvBackendUnresponsive(10_000L + MPV_UNRESPONSIVE_MS - 1, 10_000L, ownerStarted = true))
        assertTrue(mpvBackendUnresponsive(10_000L + MPV_UNRESPONSIVE_MS, 10_000L, ownerStarted = true))
    }

    @Test fun realErrorOnlyAfterFifteenSeconds() {
        assertEquals(15_000L, PREVIOUS_RELEASE_LIMIT_MS)
        assertTrue(PREVIOUS_RELEASE_LIMIT_MS > MPV_UNRESPONSIVE_MS)
    }

    @Test fun liveStreamsStopBeforeDestroy() {
        assertTrue(mpvStopsStreamBeforeDestroy(liveStream = true, broadcastSource = true))
        assertTrue(mpvStopsStreamBeforeDestroy(liveStream = false, broadcastSource = true))
        assertFalse(mpvStopsStreamBeforeDestroy(liveStream = false, broadcastSource = false))
    }

    @Test fun onlyLiveMedia3JoinsTheGate() {
        assertTrue(media3JoinsReleaseGate(isLive = true))
        assertFalse(media3JoinsReleaseGate(isLive = false))
    }

    @Test fun hintOnlyAfterAShortWait() {
        assertFalse(previousPlayerHintVisible(waiting = true, waitedMs = 0L))
        assertTrue(previousPlayerHintVisible(waiting = true, waitedMs = PREVIOUS_PLAYER_HINT_DELAY_MS))
        assertFalse(previousPlayerHintVisible(waiting = false, waitedMs = 60_000L))
    }
}
