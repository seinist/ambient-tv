package com.ambient.tv.ui.player.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRobustnessPolicyTest {
    @Test fun repeatedMpvStepsBuildOnThePendingTarget() {
        assertEquals(40_000L, mpvSeekByTarget(30_000L, 10_000L, 10_000L))
        assertEquals(20_000L, mpvSeekByTarget(null, 10_000L, 10_000L))
        assertEquals(0L, mpvSeekByTarget(5_000L, 10_000L, -10_000L))
    }

    @Test fun vlcSvpBypassedWhenSourceMeetsTarget() {
        assertFalse(vlcSvpUseful(60.0, 60.0))
        assertFalse(vlcSvpUseful(59.94, 59.94))
        assertFalse(vlcSvpUseful(60.0, 50.0))
        assertTrue(vlcSvpUseful(23.976, 60.0))
        assertTrue(vlcSvpUseful(null, 60.0))
        assertTrue(vlcSvpUseful(24.0, null))
    }
}
