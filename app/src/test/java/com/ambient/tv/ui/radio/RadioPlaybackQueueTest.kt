package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioPlaybackQueueTest {
    private fun station(id: String) = RadioStation(
        id = id,
        name = "Station $id",
        streamUrl = "https://example.test/$id",
        homepage = null,
        artworkUrl = null,
        country = null,
        countryCode = null,
        state = null,
        languages = emptyList(),
        tags = emptyList(),
        codec = "MP3",
        bitrateKbps = 128,
        isHls = false,
        votes = 0,
        clickCount = 0,
    )

    @Test fun previousAndNextFollowVisibleStationOrder() {
        val queue = RadioPlaybackQueue()
        val stations = listOf(station("a"), station("b"), station("c"))

        queue.replace(stations, "b")

        assertEquals("a", queue.previous()?.id)
        assertEquals("c", queue.next()?.id)
    }

    @Test fun edgesDoNotWrapAndDuplicatesAreRemoved() {
        val queue = RadioPlaybackQueue()
        val a = station("a")
        val b = station("b")

        queue.replace(listOf(a, b, b), "a")

        assertNull(queue.previous())
        assertEquals("b", queue.next()?.id)
        assertEquals(listOf("a", "b"), queue.snapshot().stations.map { it.id })
    }
}
