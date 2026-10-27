package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class IntroAnimationTest {
    @Test
    fun unknownKeyFallsBackToDefault() {
        assertEquals(IntroAnimation.DEFAULT, IntroAnimation.normalize("fireworks"))
        assertEquals(IntroAnimation.DEFAULT, IntroAnimation.normalize(null))
        assertEquals(IntroAnimation.OFF, IntroAnimation.normalize(IntroAnimation.OFF))
    }

    @Test
    fun everyStyleHasADurationAndOffHasNone() {
        IntroAnimation.keys.filter { it != IntroAnimation.OFF }.forEach { assert(IntroAnimation.durationMs(it) in 1500..6000) }
        assertEquals(0, IntroAnimation.durationMs(IntroAnimation.OFF))
    }

    @Test fun horizonStaysSelectableAndRetiredStylesFallBack() {
        assertEquals(IntroAnimation.HORIZON, IntroAnimation.normalize(IntroAnimation.HORIZON))
        assertEquals(true, IntroAnimation.HORIZON in IntroAnimation.keys)
        // Retired styles saved by older versions open with the default.
        for (retired in listOf("word", "power_on", "signal", "shimmer", "ripple")) {
            assertEquals(IntroAnimation.DEFAULT, IntroAnimation.normalize(retired))
        }
    }
}
