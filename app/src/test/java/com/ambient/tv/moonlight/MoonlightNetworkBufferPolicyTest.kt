package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightNetworkBufferPolicyTest {
    @Test fun growsOnlyAfterSustainedPressureAndIsBounded() {
        val policy = MoonlightNetworkBufferPolicy("balanced")
        assertEquals(MoonlightNetworkBufferPolicy.Targets(), policy.sample(12, true, 5, 60))
        assertEquals(MoonlightNetworkBufferPolicy.Targets(30, 1), policy.sample(12, true, 5, 60))
        repeat(30) { policy.sample(100, true, 40, 60) }
        assertEquals(MoonlightNetworkBufferPolicy.Targets(30, 2), policy.sample(100, true, 40, 60))
    }
    @Test fun stableSamplesShrinkSlowlyAndRespectPacingFloor() {
        val policy = MoonlightNetworkBufferPolicy("latency")
        repeat(4) { policy.sample(20, true, 5, 60) }
        repeat(9) { policy.sample(1, false, 0, 60) }
        assertEquals(MoonlightNetworkBufferPolicy.Targets(27, 1), policy.sample(1, false, 0, 60))
        repeat(100) { policy.sample(1, false, 0, 60) }
        assertEquals(MoonlightNetworkBufferPolicy.Targets(4, 0), policy.sample(1, false, 0, 60))
    }
    @Test fun missingTelemetryCannotShrinkReserve() {
        val policy = MoonlightNetworkBufferPolicy("balanced")
        repeat(40) { policy.sample(null, false, 0, 60) }
        assertEquals(MoonlightNetworkBufferPolicy.Targets(), policy.sample(null, false, 0, 60))
    }
}
