package com.ambient.tv.trailers

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class TrailerCatalogRefreshTest {
    @Test fun supersededRequestCannotRemoveTheNewRefreshOrItsBusyState() {
        val snapshot = TrailerCatalogSnapshotViewModel()
        val oldJob = Job()
        val newJob = Job()
        val old = snapshot.beginLoad(TrailerFeed.CINEMA, replace = true, job = oldJob)
        val current = snapshot.beginLoad(TrailerFeed.CINEMA, replace = true, job = newJob)
        assertTrue(oldJob.isCancelled)
        assertFalse(newJob.isCancelled)
        assertFalse(snapshot.finishLoad(TrailerFeed.CINEMA, old))
        assertSame(current, snapshot.loads[TrailerFeed.CINEMA])
        assertTrue(snapshot.finishLoad(TrailerFeed.CINEMA, current))
        assertFalse(snapshot.loads.containsKey(TrailerFeed.CINEMA))
        newJob.cancel()
    }

    @Test fun busyStartsAndStopsAreObservableEvenWhenTheDisplayedFeedDoesNotChange() {
        val snapshot = TrailerCatalogSnapshotViewModel()
        val observer = SnapshotStateObserver { it() }
        val scope = Any()
        var changes = 0
        var busy = false
        val onChange: (Any) -> Unit = { changes++ }
        fun observe() = observer.observeReads(scope, onChange) {
            busy = snapshot.loads[TrailerFeed.CINEMA]?.replace == true
        }
        observer.start()
        try {
            observe()
            assertFalse(busy)
            val request = snapshot.beginLoad(TrailerFeed.CINEMA, replace = true, job = null)
            Snapshot.sendApplyNotifications()
            assertEquals(1, changes)
            observe()
            assertTrue(busy)
            snapshot.finishLoad(TrailerFeed.CINEMA, request)
            Snapshot.sendApplyNotifications()
            assertEquals(2, changes)
            observe()
            assertFalse(busy)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun reconnectCancellationPreservesLoadedPagesAndClearsTheirTransientFlags() {
        val snapshot = TrailerCatalogSnapshotViewModel()
        val entry = TrailerEntry(TrailerTitle(1, title = "Kept"), "movie", listOf(TrailerVideo("abcdefghijk")))
        snapshot.feeds[TrailerFeed.CINEMA] = TrailerFeedUi(
            loadKey = "configured", entries = listOf(entry), page = 3, totalPages = 8,
            loadingInitial = false, refreshing = true,
        )
        snapshot.feeds[TrailerFeed.SERIES] = TrailerFeedUi(loadingInitial = true)
        val cinemaJob = Job()
        val seriesJob = Job()
        val stale = snapshot.beginLoad(TrailerFeed.CINEMA, replace = true, job = cinemaJob)
        snapshot.beginLoad(TrailerFeed.SERIES, replace = true, job = seriesJob)

        snapshot.cancelLoads()

        assertTrue(cinemaJob.isCancelled)
        assertTrue(seriesJob.isCancelled)
        assertTrue(snapshot.loads.isEmpty())
        assertFalse(snapshot.finishLoad(TrailerFeed.CINEMA, stale))
        val kept = snapshot.feeds.getValue(TrailerFeed.CINEMA)
        assertEquals(listOf(entry), kept.entries)
        assertEquals(3, kept.page)
        assertEquals(8, kept.totalPages)
        assertFalse(kept.refreshing)
        assertFalse(kept.loadingMore)
        assertNull(snapshot.feeds[TrailerFeed.SERIES])
    }
}
