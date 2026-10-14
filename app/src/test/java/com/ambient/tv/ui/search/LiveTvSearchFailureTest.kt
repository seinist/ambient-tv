package com.ambient.tv.ui.search

import com.ambient.tv.livetv.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveTvSearchFailureTest {
    private fun repository(id: String, fail: Boolean = false, cancel: Boolean = false, guideFails: Boolean = false) = object : LiveTvRepository {
        override val source = LiveTvSource(id, "iptv", id)
        override suspend fun capabilities(force: Boolean): LiveTvCapabilities {
            if (cancel) throw CancellationException("Query replaced")
            if (fail) error("Offline")
            return LiveTvCapabilities(available = true, guide = guideFails)
        }
        override suspend fun channels(force: Boolean) = listOf(LiveTvChannel(uuid = id, name = "News", number = null, iconUrl = null, sourceId = id))
        override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> {
            if (guideFails) error("Guide offline")
            return emptyList()
        }
        override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource = error("unused")
        override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource = error("unused")
    }

    @Test fun partialFailureKeepsHealthySourceResultsAndReportsTheFailure() = runBlocking {
        val (results, failed) = searchLiveTvRepositories(listOf(repository("working"), repository("offline", fail = true)), "news")
        assertEquals(listOf("working"), results.channels.map { it.sourceId })
        assertTrue(failed)
        assertEquals(SearchSourceState.PARTIAL, liveTvSearchState(results, failed))
    }

    @Test fun totalFailureIsNotReportedAsSuccessfulEmptySearch() = runBlocking {
        val (results, failed) = searchLiveTvRepositories(listOf(repository("offline", fail = true)), "news")
        assertEquals(0, results.size)
        assertTrue(failed)
        assertEquals(SearchSourceState.ERROR, liveTvSearchState(results, failed))
    }

    @Test fun failedGuideKeepsChannelMatchesButMarksResultsIncomplete() = runBlocking {
        val (results, failed) = searchLiveTvRepositories(listOf(repository("guide-offline", guideFails = true)), "news")
        assertEquals(1, results.channels.size)
        assertTrue(failed)
        assertEquals(SearchSourceState.PARTIAL, liveTvSearchState(results, failed))
    }

    @Test fun incompleteSearchWithoutMatchesIsStillPartial() = runBlocking {
        val (results, failed) = searchLiveTvRepositories(listOf(repository("guide-offline", guideFails = true)), "unmatched")
        assertEquals(0, results.size)
        assertEquals(SearchSourceState.PARTIAL, liveTvSearchState(results, failed))
    }

    @Test fun successfulEmptySearchIsReady() = runBlocking {
        val (results, failed) = searchLiveTvRepositories(listOf(repository("working")), "unmatched")
        assertEquals(0, results.size)
        assertEquals(SearchSourceState.READY, liveTvSearchState(results, failed))
    }

    @Test fun cancellationIsNotConvertedIntoProviderFailure() = runBlocking {
        try {
            searchLiveTvRepositories(listOf(repository("cancelled", cancel = true)), "news")
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
}
