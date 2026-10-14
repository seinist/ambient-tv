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

    @Test fun recognisesExistingTimersAndDoesNotMatchOtherChannels() {
        assertTrue(hasProgrammeRecording(event.copy(dvrUuid = "dvr"), emptyList()))
        val timer = LiveTvRecording("timer", "channel", "Channel", "Show",
            startEpochSeconds = 90, stopEpochSeconds = 210, status = null,
            scheduleStatus = null, url = null, imageUrl = null, fanartUrl = null, fileSizeBytes = null)
        assertTrue(hasProgrammeRecording(event, listOf(timer)))
        assertFalse(hasProgrammeRecording(event, listOf(timer.copy(channelUuid = "other"))))
        assertFalse(hasProgrammeRecording(event, listOf(timer.copy(stopEpochSeconds = 110))))
        assertTrue(hasProgrammeRecording(event, listOf(timer.copy(channelUuid = null, broadcastEventId = "event"))))
    }
}
