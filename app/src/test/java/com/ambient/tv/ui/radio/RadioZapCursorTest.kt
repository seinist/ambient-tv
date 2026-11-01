package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioSearchPage
import com.ambient.tv.data.radio.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioZapCursorTest {
    private fun station(id: String) = RadioStation(
        id = id, name = id.uppercase(), streamUrl = "https://example.test/$id",
        homepage = null, artworkUrl = null, country = null, countryCode = null, state = null,
        languages = emptyList(), tags = emptyList(), codec = null, bitrateKbps = null,
        isHls = false, votes = 0, clickCount = 0,
    )

    @Test fun everyRapidNextAdvancesFromTheRequestedStationNotStillPlayingOne() {
        val queue = RadioPlaybackQueue()
        queue.replace(listOf("a", "b", "c", "d", "e").map(::station), "a")
        val cursor = RadioZapCursor("a")
        for (id in listOf("b", "c", "d", "e")) {
            assertTrue(cursor.move(queue.snapshot(), 1))
            assertEquals(id, cursor.target(queue.snapshot())?.id)
            assertEquals("a", queue.current()?.id) // No intermediate playback / queue selection.
        }
        assertTrue(cursor.move(queue.snapshot(), -1))
        assertEquals("d", cursor.target(queue.snapshot())?.id)
    }

    @Test fun quickNextAndPreviousCanReturnToCurrentWithoutOpeningStream() {
        val queue = RadioPlaybackQueue()
        queue.replace(listOf("a", "b", "c").map(::station), "b")
        val cursor = RadioZapCursor("b")
        assertTrue(cursor.move(queue.snapshot(), 1))
        assertEquals("c", cursor.target(queue.snapshot())?.id)
        assertTrue(cursor.move(queue.snapshot(), -1))
        assertEquals("b", cursor.target(queue.snapshot())?.id)
        assertEquals(queue.current()?.id, cursor.target(queue.snapshot())?.id)
    }

    @Test fun unresolvedNextPageCountsEveryPressAndCanNavigateBackWhileLoading() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.COUNTRIES, "DE", "")
        val source = RadioBrowseQueueSource(scope, countryCode = "DE", nextOffset = 48, hasMore = true)
        queue.replace(listOf("a", "b").map(::station), "b", source)
        val cursor = RadioZapCursor("b")
        assertTrue(cursor.move(queue.snapshot(), 1))
        assertTrue(cursor.move(queue.snapshot(), 1))
        assertNull(cursor.target(queue.snapshot()))
        assertTrue(cursor.needsPage(queue.snapshot()))
        assertEquals(3, cursor.requestedIndex(queue.snapshot()))

        val page = RadioSearchPage(
            items = listOf(station("c"), station("d"), station("e")),
            offset = 48, limit = 48, hasMore = false,
        )
        assertTrue(queue.appendPage(source, page))
        assertFalse(cursor.needsPage(queue.snapshot()))
        assertEquals("d", cursor.target(queue.snapshot())?.id)
        assertEquals("b", queue.current()?.id) // Page fetch never starts a stream.
        assertTrue(cursor.move(queue.snapshot(), -1))
        assertEquals("c", cursor.target(queue.snapshot())?.id)
    }

    @Test fun previousWorksDuringUnresolvedPaginationEvenAfterNoMorePages() {
        val queue = RadioPlaybackQueue()
        val source = RadioBrowseQueueSource(
            RadioFocusScope(RadioSection.GENRES, "jazz", ""), tag = "jazz", hasMore = true,
            nextOffset = 48,
        )
        queue.replace(listOf("a", "b").map(::station), "b", source)
        val cursor = RadioZapCursor("b")
        repeat(3) { assertTrue(cursor.move(queue.snapshot(), 1)) }
        assertTrue(cursor.move(queue.snapshot(), -1))
        cursor.clampToLoaded(queue.snapshot())
        assertEquals("b", cursor.target(queue.snapshot())?.id)
    }

    @Test fun shortOrFailedLastPageClampsToLastAvailableStation() {
        val queue = RadioPlaybackQueue()
        val source = RadioBrowseQueueSource(
            RadioFocusScope(RadioSection.POPULAR, null, ""), hasMore = true, nextOffset = 48,
        )
        queue.replace(listOf("a", "b").map(::station), "a", source)
        val cursor = RadioZapCursor("a")
        repeat(5) { assertTrue(cursor.move(queue.snapshot(), 1)) }
        assertNull(cursor.target(queue.snapshot()))
        assertTrue(queue.appendPage(source,
            RadioSearchPage(listOf(station("c"), station("d")), 48, 48, hasMore = false)))
        assertNull(cursor.target(queue.snapshot()))
        cursor.clampToLoaded(queue.snapshot())
        assertEquals("d", cursor.target(queue.snapshot())?.id)
        assertFalse(cursor.canMove(queue.snapshot(), 1))
    }

    @Test fun noWrapAndRequestedStationIsStableAfterAlphabeticalPageMerge() {
        val queue = RadioPlaybackQueue()
        val scope = RadioFocusScope(RadioSection.COUNTRIES, "US", "")
        val source = RadioBrowseQueueSource(scope, countryCode = "US", nextOffset = 48, hasMore = true)
        queue.replace(listOf("b", "d").map(::station), "b", source)
        val cursor = RadioZapCursor("b")
        assertFalse(cursor.canMove(queue.snapshot(), -1))
        assertTrue(cursor.move(queue.snapshot(), 1))
        assertEquals("d", cursor.target(queue.snapshot())?.id)
        assertTrue(queue.appendPage(source, RadioSearchPage(
            listOf(station("a"), station("c"), station("e")), 48, 48, hasMore = false,
        )))
        // The base is tracked by UUID, not an index that becomes stale when pages reorder.
        assertEquals("c", cursor.target(queue.snapshot())?.id)
        assertEquals("b", queue.current()?.id)
    }
}
