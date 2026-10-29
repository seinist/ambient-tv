package com.ambient.tv.livetv

import org.junit.Assert.*
import org.junit.Test

class LiveTvDashboardMergePolicyTest {
    @Test fun recordingFailureDoesNotKeepEventsTheFreshGuideRemoved() {
        val policy = LiveTvDashboardMergePolicy(listOf("Recordings: Unavailable"))
        assertEquals(emptyList<String>(), policy.merge(LiveTvDashboardPart.EPG, emptyList(), listOf("old event")))
        assertEquals(listOf("old recording"), policy.merge(LiveTvDashboardPart.RECORDINGS, emptyList(), listOf("old recording")))
    }

    @Test fun failedGuideKeepsItsEventsButConfirmedTimerRemovalWins() {
        val policy = LiveTvDashboardMergePolicy(listOf("EPG: Timeout"))
        assertEquals(listOf("old event"), policy.merge(LiveTvDashboardPart.EPG, emptyList(), listOf("old event")))
        assertEquals(emptyList<String>(), policy.merge(LiveTvDashboardPart.TIMERS, emptyList(), listOf("old timer")))
    }

    @Test fun eachOptionalAreaOnlyFallsBackForItsOwnFailure() {
        for (failed in LiveTvDashboardPart.entries) {
            val policy = LiveTvDashboardMergePolicy(listOf("${failed.warningLabel}: Failed"))
            for (area in LiveTvDashboardPart.entries) {
                val expected = if (area == failed) listOf("cached") else emptyList()
                assertEquals("Failed $failed; checked $area", expected, policy.merge(area, emptyList(), listOf("cached")))
            }
        }
    }

    @Test fun freshContentReplacesPreviousContent() {
        val policy = LiveTvDashboardMergePolicy(listOf("EPG: Partial"))
        assertEquals(listOf("updated event"), policy.merge(LiveTvDashboardPart.EPG, listOf("updated event"), listOf("old event")))
    }

    @Test fun errorMessageMentioningAnotherAreaDoesNotInvalidateThatArea() {
        val policy = LiveTvDashboardMergePolicy(listOf("Timers: EPG: no programme"))
        assertEquals(emptyList<String>(), policy.merge(LiveTvDashboardPart.EPG, emptyList(), listOf("old event")))
        assertEquals(listOf("old timer"), policy.merge(LiveTvDashboardPart.TIMERS, emptyList(), listOf("old timer")))
    }
}
