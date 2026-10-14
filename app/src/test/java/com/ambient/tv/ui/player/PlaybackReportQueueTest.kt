package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackReportQueueTest {
    @Test fun slowBackendReceivesLatestPositionInsteadOfEveryHeartbeat() {
        val queue = PlaybackReportQueue<Int>()
        repeat(1000) { queue.put(1, it, false) }
        assertEquals(999, queue.take()!!.value)
        assertTrue(queue.isEmpty)
    }
    @Test fun oldHeartbeatCannotReplaceFinalStop() {
        val queue = PlaybackReportQueue<Int>()
        queue.put(1, 100, true); queue.put(1, 70, false)
        assertEquals(PlaybackReportQueue.Entry(100, true), queue.take())
    }
    @Test fun seekBackwardsIsPreservedAndOtherGenerationsStaySeparate() {
        val queue = PlaybackReportQueue<Int>()
        queue.put(1, 500, false); queue.put(2, 100, false); queue.put(1, 200, false)
        assertEquals(200, queue.take()!!.value); assertEquals(100, queue.take()!!.value)
        assertNull(queue.take())
    }
    @Test fun rapidStreamChangesCannotGrowPendingMetadataWithoutBound() {
        val queue = PlaybackReportQueue<Int>(3)
        repeat(1000) { queue.put(it.toLong(), it, true) }
        assertEquals(listOf(997, 998, 999), generateSequence { queue.take()?.value }.toList())
    }
}
