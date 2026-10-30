package com.ambient.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSystemPageTest {
    @Test fun countdownUsesMonotonicDeadlineAndRoundsUp() {
        assertNull(offlineRetrySeconds(0L, 1_000L))
        assertEquals(30, offlineRetrySeconds(31_000L, 1_000L))
        assertEquals(2, offlineRetrySeconds(2_001L, 1_000L))
        assertEquals(1, offlineRetrySeconds(1_001L, 1_000L))
        assertEquals(0, offlineRetrySeconds(1_000L, 1_000L))
        assertEquals(0, offlineRetrySeconds(1_000L, 99_000L))
    }
}
