package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewChangeMotionTest {
    @Test fun sparsamCutsTheViewChange() {
        val motion = viewChangeMotion(reducedEffects = true)
        assertFalse(motion.animate)
        assertEquals(0, motion.durationMillis)
        assertEquals(1f, motion.startAlpha, 0f)
    }

    @Test fun fullInterfaceFadesOneViewBriefly() {
        val motion = viewChangeMotion(reducedEffects = false)
        assertTrue(motion.animate)
        assertTrue(motion.durationMillis in 1..300)
        assertTrue(motion.startAlpha in 0.5f..0.9f)
    }
}
