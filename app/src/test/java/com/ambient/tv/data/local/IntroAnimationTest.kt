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
        IntroAnimation.keys.filter { it != IntroAnimation.OFF }.forEach { assert(IntroAnimation.durationMs(it) in 1500..4000) }
        assertEquals(0, IntroAnimation.durationMs(IntroAnimation.OFF))
    }

    @Test fun wordAndHorizonStaySelectable() {
        assertEquals(IntroAnimation.WORD, IntroAnimation.normalize(IntroAnimation.WORD))
        assertEquals(IntroAnimation.HORIZON, IntroAnimation.normalize(IntroAnimation.HORIZON))
        assertEquals(true, IntroAnimation.WORD in IntroAnimation.keys)
        assertEquals(true, IntroAnimation.HORIZON in IntroAnimation.keys)
    }
}
