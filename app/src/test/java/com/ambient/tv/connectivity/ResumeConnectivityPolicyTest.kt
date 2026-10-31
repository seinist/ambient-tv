package com.ambient.tv.connectivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeConnectivityPolicyTest {
    @Test
    fun `short background with fresh health skips recheck`() {
        assertFalse(ResumeConnectivityPolicy.shouldRecheck(backgroundedForMs = 10_000L, lastHealthAgeMs = 5_000L))
    }

    @Test
    fun `background beyond threshold triggers recheck`() {
        assertTrue(ResumeConnectivityPolicy.shouldRecheck(backgroundedForMs = 45_000L, lastHealthAgeMs = 1_000L))
    }

    @Test
    fun `stale health triggers recheck even after short background`() {
        assertTrue(ResumeConnectivityPolicy.shouldRecheck(backgroundedForMs = 1_000L, lastHealthAgeMs = 30_000L))
    }

    @Test
    fun `returning to foreground detects a recently powered-off server`() {
        assertTrue(ResumeConnectivityPolicy.shouldRecheckOnStart(true, 1_000L, 1_000L))
        assertFalse(ResumeConnectivityPolicy.shouldRecheckOnStart(false, 0L, 1_000L))
        assertTrue(ResumeConnectivityPolicy.shouldRecheckOnStart(false, 0L, 30_000L))
    }

    @Test
    fun `negative ages force recheck`() {
        assertTrue(ResumeConnectivityPolicy.shouldRecheck(backgroundedForMs = -1L, lastHealthAgeMs = 0L))
    }
}
