package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedResumeRequestTest {
    @Test fun technicalReloadDoesNotDeductFromAnAlreadyRewoundProgressPoint() {
        val request = SavedResumeRequest()
        assertEquals(20_000L, request.position(30_000, 10))
        assertEquals(20_000L, request.position(20_000, 10))
        assertEquals(25_000L, request.position(25_000, 10))
    }

    @Test fun zeroClampedStartStillConsumesThisLaunch() {
        val request = SavedResumeRequest()
        assertEquals(0L, request.position(5_000, 10))
        assertEquals(15_000L, request.position(15_000, 10))
    }

    @Test fun explicitPositionAndNonVideoStartsCannotLeaveARewindPending() {
        val request = SavedResumeRequest()
        assertEquals(40_000L, request.position(40_000, 10, eligible = false))
        assertEquals(50_000L, request.position(50_000, 10))
    }

    @Test fun existingSessionNextUpAndRetryKeepTheirPosition() {
        val request = SavedResumeRequest()
        request.skip()
        assertEquals(40_000L, request.position(40_000, 10))
        assertEquals(0L, request.position(0, 10))
    }

    @Test fun aNewUserLaunchCanRewindAgain() {
        assertEquals(20_000L, SavedResumeRequest().position(30_000, 10))
        assertEquals(50_000L, SavedResumeRequest().position(60_000, 10))
    }

    @Test fun lateMetadataCapturesOnlyTheOriginalLaunchAllowance() {
        val request = SavedResumeRequest()
        val captured = request.takeSeconds(10)
        assertEquals(20_000L, resumeRewindPositionMs(30_000, captured))
        assertEquals(0, request.takeSeconds(30))
    }
}
