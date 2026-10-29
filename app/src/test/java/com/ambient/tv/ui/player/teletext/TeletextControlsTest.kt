package com.ambient.tv.ui.player.teletext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeletextControlsTest {
    @Test fun threeDigitsSelectADecimalPageAndClearTheEntry() {
        assertEquals(TeletextPageEntry("7"), appendTeletextDigit("", 7))
        assertEquals(TeletextPageEntry("78"), appendTeletextDigit("7", 8))
        assertEquals(TeletextPageEntry("", 0x789), appendTeletextDigit("78", 9))
        assertEquals(TeletextPageEntry("", 0x100), appendTeletextDigit("10", 0))
        assertEquals(TeletextPageEntry("", 0x899), appendTeletextDigit("89", 9))
    }

    @Test fun invalidFirstDigitsCannotStartAnEntryButZeroAndNineCanFollow() {
        assertEquals(TeletextPageEntry(""), appendTeletextDigit("", 0))
        assertEquals(TeletextPageEntry(""), appendTeletextDigit("", 9))
        assertEquals(TeletextPageEntry("1"), appendTeletextDigit("1", -1))
        assertEquals(TeletextPageEntry("10"), appendTeletextDigit("1", 0))
        assertEquals(TeletextPageEntry("19"), appendTeletextDigit("1", 9))
    }

    @Test fun subpageNavigationUsesActualSubcodesAndWrapsInBothDirections() {
        val available = listOf(1, 3, 12)
        assertEquals(3, stepTeletextSubpage(1, available, true))
        assertEquals(12, stepTeletextSubpage(1, available, false))
        assertEquals(1, stepTeletextSubpage(12, available, true))
        assertEquals(3, stepTeletextSubpage(12, available, false))
        assertEquals(1, stepTeletextSubpage(null, available, true))
        assertEquals(12, stepTeletextSubpage(7, available, false))
        assertEquals(3, stepTeletextSubpage(null, listOf(3), false))
        assertNull(stepTeletextSubpage(3, emptyList(), true))
    }

    @Test fun focusFallsBackOnlyWhenTheUsedControlDisappears() {
        val links = listOf(0x100, 0, 0x300, 0x400)
        assertFalse(teletextControlDisappeared(null, emptyList(), 0, false))
        assertFalse(teletextControlDisappeared(TeletextDynamicControl.RED, links, 0, true))
        assertTrue(teletextControlDisappeared(TeletextDynamicControl.GREEN, links, 0, true))
        assertFalse(teletextControlDisappeared(TeletextDynamicControl.BLUE, links, 0, true))
        assertTrue(teletextControlDisappeared(TeletextDynamicControl.BLUE, listOf(0x100), 0, true))
        assertTrue(teletextControlDisappeared(TeletextDynamicControl.RED, emptyList(), 0, false))
        assertFalse(teletextControlDisappeared(TeletextDynamicControl.NEXT_SUBPAGE, links, 2, true))
        assertTrue(teletextControlDisappeared(TeletextDynamicControl.PREVIOUS_SUBPAGE, links, 1, true))
        assertTrue(teletextControlDisappeared(TeletextDynamicControl.NEXT_SUBPAGE, links, 0, true))
    }

    @Test fun restartingTheDecoderOnlyMovesFocusOffTheTemporarilyDisabledPageList() {
        assertTrue(teletextControlDisappeared(TeletextDynamicControl.PAGES, emptyList(), 0, false))
        assertFalse(teletextControlDisappeared(TeletextDynamicControl.PAGES, emptyList(), 0, true))
        assertFalse(teletextControlDisappeared(null, emptyList(), 0, false))
    }
}
