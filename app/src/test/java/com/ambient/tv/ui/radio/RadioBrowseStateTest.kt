package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.player.PlaybackStatus
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowseStateTest {
    @Test fun positionAndNowPlayingMetadataUpdatesDoNotInvalidateBrowseCards() = runBlocking {
        val initial = playback("station-a", wantsToPlay = true)
        val snapshots = List(120) { tick ->
            initial.copy(
                status = PlaybackStatus(positionMs = tick * 500L, durationMs = 60_000, playing = tick % 2 == 0),
                metadata = initial.metadata!!.copy(title = "Song $tick", artworkUrl = "https://example.test/$tick.jpg"),
            )
        }
        assertEquals(listOf(RadioBrowsePlayback("station-a", true)), snapshots.asFlow().radioBrowsePlayback().toList())
    }

    @Test fun pauseBufferingZapAndSourceChangesStillReachBrowseCards() = runBlocking {
        val playing = playback("station-a", wantsToPlay = true)
        val paused = playing.copy(wantsToPlay = false)
        val zapped = playback("station-b", wantsToPlay = true)
        val otherSource = zapped.copy(metadata = zapped.metadata!!.copy(source = "plex"))
        val emitted = flowOf(
            playing,
            paused,
            // Play intent changes before a buffering renderer reports playing = true.
            playing.copy(status = PlaybackStatus(playing = false)),
            zapped,
            otherSource,
            otherSource.copy(status = PlaybackStatus(positionMs = 500)),
            BackgroundPlaybackSession.Snapshot(),
            playing,
        ).radioBrowsePlayback().toList()
        assertEquals(listOf(
            RadioBrowsePlayback("station-a", true),
            RadioBrowsePlayback("station-a", false),
            RadioBrowsePlayback("station-a", true),
            RadioBrowsePlayback("station-b", true),
            RadioBrowsePlayback(),
            RadioBrowsePlayback("station-a", true),
        ), emitted)
    }

    @Test fun repeatedFocusUsesTheIndexWithoutReadingOffscreenStations() {
        val stations = CountingStations(List(4096) { station("station-$it") })
        val index = RadioStationIndex()
        assertEquals(0, index.indexOf(stations, "station-0"))
        val initialReads = stations.reads
        repeat(1024) { assertEquals(it, index.indexOf(stations, "station-$it")) }
        assertEquals(-1, index.indexOf(stations, "removed"))
        assertEquals(initialReads, stations.reads)
    }

    @Test fun replacementListsRebuildPositionsAndKeepTheFirstDuplicate() {
        val index = RadioStationIndex()
        val before = listOf(station("a"), station("b"), station("a"))
        assertEquals(0, index.indexOf(before, "a"))
        assertEquals(1, index.indexOf(before, "b"))
        val after = listOf(station("b"), station("c"))
        assertEquals(0, index.indexOf(after, "b"))
        assertEquals(1, index.indexOf(after, "c"))
        assertEquals(-1, index.indexOf(after, "a"))
    }

    @Test fun appendedPagesRevalidateTheLatestFocusedStationsPagingThreshold() {
        val index = RadioStationIndex()
        val firstPage = List(48) { station("station-$it") }
        val bothPages = firstPage + List(48) { station("station-${it + 48}") }
        fun nearEnd(stations: List<RadioStation>, id: String) = radioShouldLoadOnFocus(
            index.indexOf(stations, id), stations.size, hasMore = true, loading = false, loadingMore = false,
        )
        assertTrue(nearEnd(firstPage, "station-47"))
        // The same focused station must not immediately fetch a third page after page two arrives.
        assertFalse(nearEnd(bothPages, "station-47"))
        assertTrue(nearEnd(bothPages, "station-95"))
        assertFalse(nearEnd(firstPage, "station-95"))
    }

    private fun playback(id: String, wantsToPlay: Boolean) = BackgroundPlaybackSession.Snapshot(
        metadata = PlaybackMetadata(route = "radio/$id", title = id, source = "radio", mediaId = id),
        wantsToPlay = wantsToPlay,
    )

    private fun station(id: String) = RadioStation(
        id = id, name = id, streamUrl = "https://example.test/$id", homepage = null, artworkUrl = null,
        country = null, countryCode = null, state = null, languages = emptyList(), tags = emptyList(),
        codec = null, bitrateKbps = null, isHls = false, votes = 0, clickCount = 0,
    )

    private class CountingStations(private val stations: List<RadioStation>) : AbstractList<RadioStation>() {
        var reads = 0
            private set
        override val size: Int get() = stations.size
        override fun get(index: Int): RadioStation {
            reads++
            return stations[index]
        }
    }
}
