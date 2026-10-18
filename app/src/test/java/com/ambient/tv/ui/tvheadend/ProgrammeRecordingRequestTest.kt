package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProgrammeRecordingRequestTest {
    private val event = LiveTvProgramme("event", "channel", "Channel", "Show",
        startEpochSeconds = 100, stopEpochSeconds = 200)

    private class FakeRepository(
        var timers: List<LiveTvRecording> = emptyList(),
        val manageTimers: Boolean = true,
    ) : LiveTvRepository {
        val calls = mutableListOf<String>()
        override val source = LiveTvSource("fake", "tvheadend", "Fake")
        override suspend fun capabilities(force: Boolean) = LiveTvCapabilities(available = true, manageTimers = manageTimers)
        override suspend fun channels(force: Boolean): List<LiveTvChannel> = emptyList()
        override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> = emptyList()
        override suspend fun upcomingRecordings(force: Boolean) = timers
        override suspend fun scheduleRecording(eventId: String): Result<Unit> { calls += "scheduleRecording:$eventId"; return Result.success(Unit) }
        override suspend fun recordCurrentProgramme(event: LiveTvProgramme): Result<Unit> { calls += "recordCurrentProgramme:${event.eventId}"; return Result.success(Unit) }
        override suspend fun scheduleSeries(eventId: String): Result<Unit> { calls += "scheduleSeries:$eventId"; return Result.success(Unit) }
        override suspend fun cancelRecording(uuid: String): Result<Unit> { calls += "cancelRecording:$uuid"; return Result.success(Unit) }
        override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource = error("unused")
        override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource = error("unused")
    }

    private fun timer(uuid: String = "timer") = LiveTvRecording(uuid, "channel", "Channel", "Show",
        startEpochSeconds = 150, stopEpochSeconds = 200, status = null, scheduleStatus = "recording",
        url = null, imageUrl = null, fanartUrl = null, fileSizeBytes = null)

    @Test fun guideRecordsRunningProgrammeFromNow() = runBlocking {
        val repo = FakeRepository()
        assertTrue(scheduleProgrammeRecording(repo, event, series = false, nowEpochSeconds = 150).isSuccess)
        assertEquals(listOf("recordCurrentProgramme:event"), repo.calls)
    }

    @Test fun guideSchedulesFutureProgrammeByEvent() = runBlocking {
        for (now in listOf(50L, 99L)) {
            val repo = FakeRepository()
            scheduleProgrammeRecording(repo, event, series = false, nowEpochSeconds = now).getOrThrow()
            assertEquals(listOf("scheduleRecording:event"), repo.calls)
        }
    }

    @Test fun guideCreatesSeriesTimerEvenForRunningProgramme() = runBlocking {
        for (now in listOf(50L, 150L)) {
            val repo = FakeRepository()
            scheduleProgrammeRecording(repo, event, series = true, nowEpochSeconds = now).getOrThrow()
            assertEquals(listOf("scheduleSeries:event"), repo.calls)
        }
    }

    /** Regression guard: an EPG-linked timer would start at the elapsed programme start. */
    @Test fun playerStartNeverSchedulesByEvent() = runBlocking {
        val repo = FakeRepository()
        toggleCurrentProgrammeRecording(repo, event, stopping = false)
        assertEquals(listOf("recordCurrentProgramme:event"), repo.calls)
        assertFalse(repo.calls.any { it.startsWith("scheduleRecording") })
    }

    @Test fun playerDoesNotDuplicateExistingTimerAndStopsReportedTimer() = runBlocking {
        val repo = FakeRepository(timers = listOf(timer()))
        toggleCurrentProgrammeRecording(repo, event, stopping = false)
        assertTrue(repo.calls.isEmpty())
        toggleCurrentProgrammeRecording(repo, event, stopping = true)
        assertEquals(listOf("cancelRecording:timer"), repo.calls)
        repo.calls.clear(); repo.timers = emptyList()
        toggleCurrentProgrammeRecording(repo, event, stopping = true)
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun playerRequiresTimerPermission() = runBlocking {
        val repo = FakeRepository(manageTimers = false)
        assertTrue(runCatching { toggleCurrentProgrammeRecording(repo, event, stopping = false) }.isFailure)
        assertTrue(repo.calls.isEmpty())
    }

    /** The player view model must keep delegating to the tested helper, not schedule by EPG event. */
    @Test fun playerViewModelDelegatesRecordingStart() {
        val source = java.io.File("src/main/java/com/ambient/tv/ui/tvheadend/TvheadendPlayerViewModel.kt").readText()
        val body = source.substringAfter("private fun recordCurrentProgramme()").substringBefore("private val recordingClient")
        assertTrue(body.contains("toggleCurrentProgrammeRecording(repository, event, stopping)"))
        assertFalse(source.contains("scheduleRecording("))
    }
}
