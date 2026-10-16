package com.ambient.tv.tvheadend.data

import com.ambient.tv.livetv.LiveTvProgramme
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class TvheadendRecordingTest {
    private val event = LiveTvProgramme("123", "channel", "Channel", "Current show",
        startEpochSeconds = 100, stopEpochSeconds = 200)

    @Test fun recordsRemainingProgrammeFromNowWithSelectedProfile() {
        val conf = currentProgrammeRecordingConfig(event, 150, "profile")
        assertEquals(150L, conf.getValue("start").jsonPrimitive.long)
        assertEquals(200L, conf.getValue("stop").jsonPrimitive.long)
        assertEquals("channel", conf.getValue("channel").jsonPrimitive.content)
        assertEquals("profile", conf.getValue("config_name").jsonPrimitive.content)
        assertEquals("Current show", conf.getValue("title").jsonObject.getValue("und").jsonPrimitive.content)
        assertFalse(conf.containsKey("broadcast"))
    }

    @Test fun keepsServerDefaultProfileWhenNoneSelected() {
        assertFalse(currentProgrammeRecordingConfig(event, 100, "").containsKey("config_name"))
    }

    @Test fun rejectsExpiredAndFutureProgrammesIncludingEndBoundary() {
        for (now in listOf(99L, 200L, 250L)) {
            assertTrue(runCatching { currentProgrammeRecordingConfig(event, now, "") }.isFailure)
        }
    }
    @Test fun stopsActiveRecordingsWithoutAbortingAndCancelsOnlyFutureTimers() {
        assertEquals("api/dvr/entry/stop", recordingTerminationPath("recording"))
        assertEquals("api/dvr/entry/stop", recordingTerminationPath("recordingError"))
        assertEquals("api/dvr/entry/cancel", recordingTerminationPath("scheduled"))
        for (status in listOf(null, "completed", "completedError", "unknown")) {
            assertTrue(runCatching { recordingTerminationPath(status) }.isFailure)
        }
    }
}
