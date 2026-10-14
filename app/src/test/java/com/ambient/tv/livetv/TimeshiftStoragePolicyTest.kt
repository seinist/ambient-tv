package com.ambient.tv.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeshiftStoragePolicyTest {
    @Test fun initializationPersistsOnceAndPreservesExplicitChoices() {
        val backing = com.ambient.tv.data.local.memoryPreferences()
        val prefs = com.ambient.tv.data.local.AppPreferences(backing)
        prefs.initializeLiveTimeshiftSize(512)
        prefs.initializeLiveTimeshiftSize(4096)
        assertEquals(512, com.ambient.tv.data.local.AppPreferences(backing).liveTimeshiftMb)
        for (chosen in listOf(0, 1024, 2048, 4096)) {
            prefs.liveTimeshiftMb = chosen
            prefs.initializeLiveTimeshiftSize(512)
            assertEquals(chosen, prefs.liveTimeshiftMb)
        }
    }

    @Test fun selectsConservativeSizesAcrossDevices() {
        for ((freeMb, expected) in listOf(-1L to 0, 0L to 0, 1024L to 0, 1535L to 0, 1536L to 512, 3300L to 512, 5119L to 512,
            5120L to 512, 10240L to 1024, 20480L to 2048, 40960L to 4096, 100000L to 4096)) {
            assertEquals(expected, recommendedTimeshiftMb(freeMb * 1024L * 1024L))
        }
    }

    @Test fun neverExceedsBudgetOrConsumesReserve() {
        for (freeMb in 0L..100000L step 137) {
            val size = recommendedTimeshiftMb(freeMb * 1024L * 1024L)
            assertTrue(size <= maxOf(512L, freeMb / 10))
            if (size > 0) assertTrue(freeMb - size >= 1024)
        }
    }
}
