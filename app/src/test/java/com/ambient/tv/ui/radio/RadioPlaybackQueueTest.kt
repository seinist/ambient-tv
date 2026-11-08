package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPlaybackQueueTest {
    @Test fun returnPreviewContainsTheLastStationBeforeItIsConsumed() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.POPULAR, null, "")
        queue.replace(listOf(station("a"), station("b")), "a", RadioBrowseQueueSource(scope))
        queue.select("b")
        val snapshot = queue.pendingReturn.value!!
        val prepared = prepareRadioPlaybackReturn(RadioUiState(stations = listOf(station("a"))), snapshot)!!
        assertEquals(listOf("a", "b"), prepared.stations.map { it.id })
        assertEquals(snapshot.returnRevision, prepared.playbackReturnRevision)
        assertTrue(prepared.contentReady)
        assertFalse(prepared.loading)
        assertEquals("b", queue.pendingReturn.value?.currentId)
        assertTrue(queue.commitReturn(scope, snapshot.returnRevision) {
            assertEquals(snapshot, queue.pendingReturn.value)
        })
        assertNull(queue.pendingReturn.value)
        assertFalse(queue.commitReturn(scope, snapshot.returnRevision) { error("already consumed") })
    }

    @Test fun aLaterMiniPlayerEventCannotBeConsumedByAnEarlierEntry() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.POPULAR, null, "")
        queue.replace(listOf(station("a"), station("b")), "a", RadioBrowseQueueSource(scope))
        val earlier = queue.pendingReturn.value!!
        queue.select("b")
        assertFalse(queue.commitReturn(scope, earlier.returnRevision) { error("stale entry") })
        assertEquals("b", queue.pendingReturn.value?.currentId)
        assertNull(prepareRadioPlaybackReturn(RadioUiState(section = RadioSection.FAVORITES), queue.pendingReturn.value))
    }

    @Test fun aNewRouteCannotPreviewThePreviousSectionsQueueWhileItsStateCatchesUp() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.POPULAR, null, "")
        queue.replace(listOf(station("shared"), station("last")), "last", RadioBrowseQueueSource(scope))
        val staleState = RadioUiState(section = RadioSection.POPULAR, stations = listOf(station("shared")))
        assertNull(prepareRadioPlaybackReturn(staleState, queue.pendingReturn.value, RadioSection.FAVORITES))
        // Refusing that route leaves the return available for its actual origin.
        assertEquals("last", queue.pendingReturn.value?.currentId)
        assertEquals(listOf("shared", "last"),
            prepareRadioPlaybackReturn(staleState, queue.pendingReturn.value, RadioSection.POPULAR)!!.stations.map { it.id })
    }

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

    @Test fun openingThirdRadioStationKeepsBothPredecessorsInItsOriginalList() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.COUNTRIES, "DE", "")
        val source = RadioBrowseQueueSource(scope, countryCode = "DE", nextOffset = 48, hasMore = true)
        queue.replace(listOf(station("one"), station("two"), station("three"), station("four")),
            "three", source)

        assertEquals("two", queue.previous()?.id)
        assertEquals("four", queue.next()?.id)
        queue.select("two")
        assertEquals("one", queue.previous()?.id)
        assertEquals("three", queue.next()?.id)
        queue.select("one")
        assertNull(queue.previous())
        assertEquals("two", queue.next()?.id)

        // Switching the current station never drops the exact originating country.
        assertEquals(scope, queue.snapshot().source?.focusScope)
        assertEquals("DE", queue.snapshot().source?.countryCode)
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
    @Test fun nextPageKeepsOriginalCategoryAndRawOffset() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.GENRES, "rock", "")
        val source = RadioBrowseQueueSource(scope, tag = "rock", nextOffset = 48, hasMore = true)
        queue.replace(listOf(station("a"), station("b")), "b", source)
        assertNull(queue.next())
        val page = com.ambient.tv.data.radio.RadioSearchPage(
            items = listOf(station("b"), station("c")),
            offset = 48, limit = 48, hasMore = false,
        )
        assertFalse(queue.appendPage(source.copy(tag = "jazz"), page))
        assertTrue(queue.appendPage(source, page))
        assertEquals(listOf("a", "b", "c"), queue.snapshot().stations.map { it.id })
        assertEquals("c", queue.next()?.id)
        assertEquals(96, queue.snapshot().source?.nextOffset)
        assertFalse(queue.snapshot().source!!.hasMore)
        assertFalse(queue.appendPage(source, page))
        queue.select("c")
        assertNull(queue.next())
        assertEquals("c", queue.consumeReturn(scope)?.currentId)
        assertNull(queue.consumeReturn(scope))
    }

    @Test fun appendedPagePreservesServerCursorOrderAndReverseFlag() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.COUNTRIES, "DE", "")
        val source = RadioBrowseQueueSource(scope, countryCode = "DE",
            nextOffset = 48, hasMore = true, sortDescending = true)
        queue.replace(listOf(station("z"), station("b")), "z", source)
        val page = com.ambient.tv.data.radio.RadioSearchPage(
            items = listOf(station("a"), station("c")), offset = 48, limit = 48, hasMore = false,
        )
        assertTrue(queue.appendPage(source, page))
        assertEquals(listOf("z", "b", "a", "c"), queue.snapshot().stations.map { it.id })
        assertEquals(true, queue.snapshot().source?.sortDescending)
        queue.select("b")
        assertEquals("a", queue.next()?.id)
    }

    @Test fun coldStartClearsOnlyReturnFocusAndPreservesThePlayingQueue() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.POPULAR, null, "")
        queue.replace(listOf(station("a"), station("b")), "b", RadioBrowseQueueSource(scope))
        assertTrue(queue.snapshot().returnPending)
        queue.clearReturnFocus()
        assertFalse(queue.snapshot().returnPending)
        assertNull(queue.consumeReturn(scope))
        assertEquals("b", queue.current()?.id)
        assertEquals("a", queue.previous()?.id)
    }

    @Test fun returnFocusNeverEscapesTheOriginalBrowseScope() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.COUNTRIES, "DE", "")
        queue.replace(listOf(station("de"), station("other")), "de",
            RadioBrowseQueueSource(scope, countryCode = "DE"))
        queue.select("other")
        assertNull(queue.consumeReturn(RadioFocusScope(RadioSection.POPULAR, null, "")))
        assertEquals("other", queue.consumeReturn(scope)?.currentId)
    }

}
