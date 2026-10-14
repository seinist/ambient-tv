package com.ambient.tv.ui.common

import com.ambient.tv.ui.player.SleepTimerKind
import org.junit.Assert.*
import org.junit.Test

class SleepTimerControlsTest {
    @Test fun onlyFixedDurationHasAnAbsoluteDeadline() {
        assertEquals(901000L, sleepConfig(SleepTimerKind.DURATION, 15, 1000L).deadlineRealtimeMs)
        for (kind in listOf(SleepTimerKind.OFF, SleepTimerKind.END_OF_ITEM, SleepTimerKind.PAUSE_INACTIVITY, SleepTimerKind.USER_INACTIVITY)) {
            assertNull(sleepConfig(kind, 30, 1000L).deadlineRealtimeMs)
        }
    }
    @Test fun pauseRetainsShortDurationsAndNonTimedTriggersHaveNoDuration() {
        assertTrue(5 in sleepDurations(SleepTimerKind.PAUSE_INACTIVITY))
        assertFalse(5 in sleepDurations(SleepTimerKind.USER_INACTIVITY))
        assertTrue(sleepDurations(SleepTimerKind.END_OF_ITEM).isEmpty())
        assertTrue(sleepDurations(SleepTimerKind.OFF).isEmpty())
    }
}
