package com.swordfish.libretrodroid

import org.junit.Assert.*
import org.junit.Test

class IdlePresentationPolicyTest {
    @Test fun runningBothRenderersRetainContinuousEmulation() {
        assertTrue(IdlePresentationPolicy.continuous(true, true, true))
        assertFalse(IdlePresentationPolicy.wakeForWork(true, true, true))
    }

    @Test fun foregroundPauseSleepsAndEveryAcceptedMutationWakes() {
        assertFalse(IdlePresentationPolicy.continuous(true, false, true))
        assertTrue(IdlePresentationPolicy.wakeForWork(true, false, true))
        assertTrue(IdlePresentationPolicy.continuous(true, true, true))
    }

    @Test fun lifecycleAndPreFirstFramePreserveOriginalContract() {
        for (ready in listOf(false, true)) {
            for (enabled in listOf(false, true)) {
                assertFalse(IdlePresentationPolicy.continuous(false, ready, enabled))
                assertFalse(IdlePresentationPolicy.wakeForWork(false, ready, enabled))
            }
            assertTrue(IdlePresentationPolicy.continuous(true, ready, false))
            assertFalse(IdlePresentationPolicy.wakeForWork(true, ready, false))
        }
    }
}
