package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DpadNavigationAccelerationTest {
    @Test
    fun singlePressesAndEarlyRepeatsStayPrecise() {
        assertEquals(1, dpadNavigationRepeatSteps(0))
        assertEquals(1, dpadNavigationRepeatSteps(7))
        assertEquals(1, dpadNavigationRepeatSteps(14))
    }

    @Test
    fun heldDirectionAcceleratesLogarithmicallyAndIsBounded() {
        assertEquals(2, dpadNavigationRepeatSteps(15))
        assertEquals(3, dpadNavigationRepeatSteps(31))
        assertEquals(4, dpadNavigationRepeatSteps(63))
        assertEquals(4, dpadNavigationRepeatSteps(Int.MAX_VALUE))
    }
}
