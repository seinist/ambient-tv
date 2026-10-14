package com.ambient.tv.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTransitionsTest {
    private fun rec(uuid: String, start: Long, stop: Long) = LiveTvRecording(
        uuid = uuid, channelUuid = null, channelName = "Das Erste", title = "Tatort $uuid",
        startEpochSeconds = start, stopEpochSeconds = stop, status = null, scheduleStatus = null,
        url = null, imageUrl = null, fanartUrl = null, fileSizeBytes = null,
    )
    private fun snap(upcoming: List<LiveTvRecording> = emptyList(), finished: Set<String> = emptySet(), failed: Set<String> = emptySet()) =
        RecordingSnapshot(upcoming.associateBy { it.uuid }, finished, failed)

    @Test fun firstPollIsOnlyABaseline() {
        val starts = mutableSetOf<String>()
        val events = recordingEvents(null, snap(listOf(rec("a", 0, 100)), finished = setOf("old")), 50, starts)
        assertTrue(events.isEmpty())
        assertEquals(setOf("a"), starts)
        // Already running at the baseline: no "started" on the next poll either.
        assertTrue(recordingEvents(snap(listOf(rec("a", 0, 100))), snap(listOf(rec("a", 0, 100))), 60, starts).isEmpty())
    }

    @Test fun startFinishAndFailureAreAnnouncedOnce() {
        val starts = mutableSetOf<String>()
        val scheduled = snap(listOf(rec("a", 100, 200), rec("b", 100, 200)))
        recordingEvents(null, scheduled, 50, starts)
        val started = recordingEvents(scheduled, scheduled, 120, starts)
        assertEquals(listOf(RecordingEventKind.STARTED, RecordingEventKind.STARTED), started.map { it.kind })
        assertTrue(recordingEvents(scheduled, scheduled, 130, starts).isEmpty())

        val done = snap(finished = setOf("a"), failed = setOf("b"))
        val ended = recordingEvents(scheduled, done, 210, starts)
        assertEquals(setOf(RecordingEventKind.FINISHED to "a", RecordingEventKind.FAILED to "b"),
            ended.map { it.kind to it.recording.uuid }.toSet())
        assertTrue(starts.isEmpty())
        assertTrue(recordingEvents(done, done, 220, starts).isEmpty())
    }

    @Test fun recordingsNeverSeenScheduledStayQuiet() {
        val starts = mutableSetOf<String>()
        val before = snap(finished = setOf("x"))
        val after = snap(finished = setOf("x", "paged-in"), failed = setOf("other"))
        assertTrue(recordingEvents(before, after, 0, starts).isEmpty())
    }
}
