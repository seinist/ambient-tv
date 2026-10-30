package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandheldSwipeTabsTest {
    @Test
    fun `left swipe advances to next tab in ltr`() {
        assertEquals(
            2,
            swipeTabTargetIndex(
                selectedIndex = 1,
                tabCount = 4,
                horizontalDistance = -90f,
                verticalDistance = 8f,
                threshold = 60f,
                rtl = false,
            ),
        )
    }

    @Test
    fun `right swipe returns to previous tab in ltr`() {
        assertEquals(
            0,
            swipeTabTargetIndex(
                selectedIndex = 1,
                tabCount = 4,
                horizontalDistance = 90f,
                verticalDistance = 8f,
                threshold = 60f,
                rtl = false,
            ),
        )
    }

    @Test
    fun `rtl reverses logical swipe direction`() {
        assertEquals(
            0,
            swipeTabTargetIndex(
                selectedIndex = 1,
                tabCount = 4,
                horizontalDistance = -90f,
                verticalDistance = 8f,
                threshold = 60f,
                rtl = true,
            ),
        )
    }

    @Test
    fun `short or mostly vertical drag does not switch tabs`() {
        assertNull(swipeTabTargetIndex(1, 4, -40f, 0f, 60f, false))
        assertNull(swipeTabTargetIndex(1, 4, -90f, 80f, 60f, false))
    }

    @Test
    fun `swipe does not wrap around first or last tab`() {
        assertNull(swipeTabTargetIndex(0, 4, 90f, 0f, 60f, false))
        assertNull(swipeTabTargetIndex(3, 4, -90f, 0f, 60f, false))
    }

    @Test
    fun `invalid selection or single tab is ignored`() {
        assertNull(swipeTabTargetIndex(-1, 4, -90f, 0f, 60f, false))
        assertNull(swipeTabTargetIndex(0, 1, -90f, 0f, 60f, false))
    }
}
