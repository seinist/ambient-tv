package com.ambient.tv.livetv

import com.ambient.tv.ui.tvheadend.zapChannels
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LiveTvZapOrderTest {
    private fun channel(id: String) = LiveTvChannel(id, id, null, null)
    private val all = listOf("a", "b", "c", "d").map(::channel)

    @After fun reset() { LiveTvZapOrder.clear(); LiveTvZapOrder.forPlayer("none", "none", reopened = false) }

    @Test fun offerIsTakenOnceForTheStartedChannel() {
        LiveTvZapOrder.offer("tvheadend:x", listOf("c", "a"), nowMs = 1_000)
        assertEquals(listOf("c", "a"), LiveTvZapOrder.take("tvheadend:x", "a", nowMs = 2_000))
        assertNull(LiveTvZapOrder.take("tvheadend:x", "a", nowMs = 2_000))
    }

    @Test fun staleOrForeignOffersAreDropped() {
        LiveTvZapOrder.offer("tvheadend:x", listOf("a"), nowMs = 0)
        assertNull(LiveTvZapOrder.take("iptv:y", "a", nowMs = 10))
        LiveTvZapOrder.offer("tvheadend:x", listOf("a"), nowMs = 0)
        assertNull(LiveTvZapOrder.take("tvheadend:x", "b", nowMs = 10))
        LiveTvZapOrder.offer("tvheadend:x", listOf("a"), nowMs = 0)
        assertNull(LiveTvZapOrder.take("tvheadend:x", "a", nowMs = 60_000))
    }

    @Test fun reopenedPlaybackKeepsItsOrderANewOneDoesNot() {
        LiveTvZapOrder.offer("tvheadend:x", listOf("b", "c"), nowMs = 0)
        assertEquals(listOf("b", "c"), LiveTvZapOrder.forPlayer("tvheadend:x", "b", reopened = false, nowMs = 10))
        // Back from the mini-player after zapping to c: same order.
        assertEquals(listOf("b", "c"), LiveTvZapOrder.forPlayer("tvheadend:x", "c", reopened = true, nowMs = 99_000))
        // A new playback from Home: all channels, and the old order is gone.
        assertNull(LiveTvZapOrder.forPlayer("tvheadend:x", "c", reopened = false, nowMs = 99_000))
        assertNull(LiveTvZapOrder.forPlayer("tvheadend:x", "c", reopened = true, nowMs = 99_000))
    }

    @Test fun zapRingFollowsTheFilteredOrder() {
        assertEquals(listOf("d", "b"), zapChannels(all, listOf("d", "x", "b")).map { it.uuid })
        assertEquals(all, zapChannels(all, null))
        assertEquals(all, zapChannels(all, listOf("gone")))
    }
}
