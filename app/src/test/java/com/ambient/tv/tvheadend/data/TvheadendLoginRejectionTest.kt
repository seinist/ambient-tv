package com.ambient.tv.tvheadend.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvheadendLoginRejectionTest {
    private val probe = TvheadendLoginRejection.PROBE_PATH
    private val start = 1_000_000L

    @Test fun singleServerinfo401DoesNotReject() {
        val policy = TvheadendLoginRejection()
        assertFalse(policy.on401(probe, explicit = false, portReachable = true, lastWakeAtMs = 0L, nowMs = start))
    }

    @Test fun two401sAtLeastThirtySecondsApartReject() {
        val policy = TvheadendLoginRejection()
        assertFalse(policy.on401(probe, false, true, 0L, start))
        assertFalse(policy.on401(probe, false, true, 0L, start + 29_999L))
        assertTrue(policy.on401(probe, false, true, 0L, start + 30_000L))
    }

    @Test fun parallelBurstOf401sDoesNotReject() {
        val policy = TvheadendLoginRejection()
        repeat(5) { assertFalse(policy.on401(probe, false, true, 0L, start + it * 100L)) }
    }

    @Test fun successBetween401sResetsTheCount() {
        val policy = TvheadendLoginRejection()
        assertFalse(policy.on401(probe, false, true, 0L, start))
        policy.onSuccess()
        assertFalse(policy.on401(probe, false, true, 0L, start + 40_000L))
        assertTrue(policy.on401(probe, false, true, 0L, start + 70_000L))
    }

    @Test fun four01sWithinSixtySecondsAfterWakeDoNotReject() {
        val policy = TvheadendLoginRejection()
        val wake = start
        assertFalse(policy.on401(probe, false, true, wake, wake + 5_000L))
        assertFalse(policy.on401(probe, false, true, wake, wake + 40_000L))
        assertFalse(policy.on401(probe, false, true, wake, wake + 59_999L))
        // Settled after the grace period: counting starts only now.
        assertFalse(policy.on401(probe, false, true, wake, wake + 60_000L))
        assertTrue(policy.on401(probe, false, true, wake, wake + 90_000L))
    }

    @Test fun unreachablePortDoesNotCount() {
        val policy = TvheadendLoginRejection()
        assertFalse(policy.on401(probe, false, portReachable = false, lastWakeAtMs = 0L, nowMs = start))
        assertFalse(policy.on401(probe, false, portReachable = false, lastWakeAtMs = 0L, nowMs = start + 60_000L))
        assertFalse(policy.on401(probe, false, portReachable = true, lastWakeAtMs = 0L, nowMs = start + 61_000L))
    }

    @Test fun otherPathsNeverReject() {
        val policy = TvheadendLoginRejection()
        listOf("api/channel/grid", "api/epg/events/grid", "api/dvr/entry/grid_upcoming").forEach { path ->
            assertFalse(policy.on401(path, false, true, 0L, start))
            assertFalse(policy.on401(path, false, true, 0L, start + 120_000L))
        }
    }

    @Test fun explicitSettingsTestRejectsAtOnce() {
        val policy = TvheadendLoginRejection()
        assertTrue(policy.on401(probe, explicit = true, portReachable = true, lastWakeAtMs = start, nowMs = start + 1_000L))
    }
}
