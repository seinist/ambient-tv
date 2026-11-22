package com.ambient.tv.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionPadInputsTest {
    @Test fun removingTouchOverlayPreservesHardwareHoldsAndViceVersa() {
        val input = CompanionPadInputs()
        input.touchButtons = 1 or 2
        input.hardwareButtons = 2 or 4
        assertEquals(7, input.buttons)
        input.clearTouch()
        assertEquals(6, input.buttons)
        input.touchButtons = 8
        input.clearHardware()
        assertEquals(8, input.buttons)
        input.clear()
        assertEquals(0, input.buttons)
    }

    @Test fun strongerAxisWinsAndReleaseReturnsToTheOtherInput() {
        val input = CompanionPadInputs()
        input.touchStick(0, .6f, -.4f)
        input.hardwareAxis(0, -.9f)
        input.hardwareAxis(1, .2f)
        assertEquals(-.9f, input.axis(0), 0f)
        assertEquals(-.4f, input.axis(1), 0f)
        input.clearHardware()
        assertEquals(.6f, input.axis(0), 0f)
        input.clearTouch()
        assertEquals(0f, input.axis(0), 0f)
        assertEquals(0f, input.axis(1), 0f)
    }
}
