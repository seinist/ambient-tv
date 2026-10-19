package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class SleepWarningKeyGateTest {
    @Test fun okExtendsOnlyOnAPlayerWithHiddenControlsAndNoPanel() {
        assertTrue(sleepWarningTakesOk(true, true, listOf(false), 0))
        assertFalse(sleepWarningTakesOk(false, true, listOf(false), 0))
        assertFalse(sleepWarningTakesOk(true, false, listOf(false), 0))
        assertFalse(sleepWarningTakesOk(true, true, emptyList(), 0))
        assertFalse(sleepWarningTakesOk(true, true, listOf(false, true), 0))
        assertFalse(sleepWarningTakesOk(true, true, listOf(false), 1))
    }
}
