package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.*
import org.junit.Assert.*
import org.junit.Test

class LiveRecordingControlTest {
    private val event = LiveTvProgramme("event", "channel", "Channel", "Show",
        startEpochSeconds = 100, stopEpochSeconds = 200)
    private val supported = LiveTvCapabilities(available = true, manageTimers = true)

    @Test fun requiresBackendPermissionAndCurrentProgramme() {
        assertTrue(canRecordProgramme(supported, event, 150))
        assertFalse(canRecordProgramme(supported.copy(manageTimers = false), event, 150))
        assertFalse(canRecordProgramme(supported.copy(available = false), event, 150))
        assertFalse(canRecordProgramme(supported, null, 150))
        assertFalse(canRecordProgramme(supported, event, 99))
        assertFalse(canRecordProgramme(supported, event, 200))
        assertFalse(canRecordProgramme(supported, event.copy(eventId = ""), 150))
    }

    @Test fun staleEpgReferenceDoesNotProvideATimerToStop() {
        assertNull(findProgrammeRecording(event.copy(dvrUuid = "finished"), emptyList()))
    }

    @Test fun recognisesExistingTimersAndDoesNotMatchOtherChannels() {
        assertTrue(hasProgrammeRecording(event.copy(dvrUuid = "dvr"), emptyList()))
        val timer = LiveTvRecording("timer", "channel", "Channel", "Show",
            startEpochSeconds = 90, stopEpochSeconds = 210, status = null,
            scheduleStatus = null, url = null, imageUrl = null, fanartUrl = null, fileSizeBytes = null)
        assertTrue(hasProgrammeRecording(event, listOf(timer)))
        assertTrue(hasProgrammeRecording(event, listOf(timer.copy(startEpochSeconds = 150))))
        assertFalse(hasProgrammeRecording(event, listOf(timer.copy(startEpochSeconds = 200))))
        assertFalse(hasProgrammeRecording(event, listOf(timer.copy(channelUuid = "other"))))
        assertFalse(hasProgrammeRecording(event, listOf(timer.copy(stopEpochSeconds = 110))))
        assertTrue(hasProgrammeRecording(event, listOf(timer.copy(channelUuid = null, broadcastEventId = "event"))))
    }
    @Test fun distinguishesConfirmedRecordingFromScheduledTimers() {
        val timer = LiveTvRecording("timer", "channel", "Channel", "Show",
            startEpochSeconds = 90, stopEpochSeconds = 210, status = null,
            scheduleStatus = "scheduled", url = null, imageUrl = null, fanartUrl = null, fileSizeBytes = null)
        assertFalse(timer.isActivelyRecording())
        assertTrue(timer.copy(scheduleStatus = "recording").isActivelyRecording())
        assertTrue(timer.copy(scheduleStatus = "recordingError").isActivelyRecording())
        assertTrue(timer.copy(status = "Recording").isActivelyRecording())
        assertTrue(timer.copy(status = "InProgress").isActivelyRecording())
        assertFalse(timer.copy(scheduleStatus = "completed").isActivelyRecording())
        assertFalse(timer.copy(scheduleStatus = "failed", status = "No input detected").isActivelyRecording())
        assertFalse(timer.copy(scheduleStatus = null).isActivelyRecording())
    }
}
