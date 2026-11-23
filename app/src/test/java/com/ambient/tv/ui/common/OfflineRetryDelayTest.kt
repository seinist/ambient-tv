package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineRetryDelayTest {
    @Test fun retriesBecomeRarerAndStopGrowingAtFiveMinutes() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L), (0..5).map(::offlineRetryDelayMs))
    }
}
