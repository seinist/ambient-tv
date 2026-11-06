package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProgressReportTest {
    @Test fun aTemporaryDecoderZeroCannotEraseTheSavedCardPosition() {
        assertNull(youtubeProgressReport(0L, 600_000L, ended = false))
        assertNull(youtubeProgressReport(-1L, 600_000L, ended = false))
        // Stop/error before the decoder starts must not reset a previously watched item.
        assertNull(youtubeProgressReport(0L, 600_000L, ended = false, failed = true))
    }

    @Test fun anExplicitSeekToTheBeginningStillSavesZero() {
        val report = requireNotNull(youtubeProgressReport(0L, 600_000L, ended = false, explicitSeek = true))
        assertEquals(0L, report.positionMs)
        assertFalse(report.completed)
    }

    @Test fun realProgressRemainsSaveableWithoutWatchNextMetadataOrDuration() {
        val report = requireNotNull(youtubeProgressReport(12_345L, 0L, ended = false))
        assertEquals(12_345L, report.positionMs)
        assertFalse(report.completed)
    }

    @Test fun youtubeUsesItsOwn95PercentBoundaryRatherThanTheLibrary90PercentRule() {
        for (position in listOf(90_000L, 94_999L)) {
            val report = requireNotNull(youtubeProgressReport(position, 100_000L, ended = false))
            assertEquals(position, report.positionMs)
            assertFalse(report.completed)
        }
        val report = requireNotNull(youtubeProgressReport(95_000L, 100_000L, ended = false))
        assertEquals(100_000L, report.positionMs)
        assertTrue(report.completed)
    }

    @Test fun eofRecordsTheWholeVideoEvenWhenTheNativePlayerClearsItsClock() {
        val report = requireNotNull(youtubeProgressReport(0L, 100_000L, ended = true))
        assertEquals(100_000L, report.positionMs)
        assertTrue(report.completed)
        assertTrue(requireNotNull(youtubeProgressReport(0L, 0L, ended = true)).completed)
    }

    @Test fun aDecoderErrorDoesNotPretendTheVideoEndedSuccessfully() {
        val report = requireNotNull(youtubeProgressReport(40_000L, 100_000L, ended = true, failed = true))
        assertEquals(40_000L, report.positionMs)
        assertFalse(report.completed)
        assertNull(youtubeProgressReport(0L, 100_000L, ended = true, failed = true))
    }

    @Test fun aConfirmedTerminalSponsorHandoffCompletesEvenALongOutro() {
        val report = requireNotNull(youtubeProgressReport(40_000L, 100_000L, ended = false,
            completedByNavigation = true))
        assertEquals(100_000L, report.positionMs)
        assertTrue(report.completed)
        // Merely stopping or manually choosing Next at the same position is not completion.
        assertFalse(requireNotNull(youtubeProgressReport(40_000L, 100_000L, ended = false)).completed)
    }
}
