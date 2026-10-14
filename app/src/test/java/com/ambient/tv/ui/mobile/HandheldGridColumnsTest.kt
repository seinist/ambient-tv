package com.ambient.tv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldGridColumnsTest {
    @Test fun `phone landscape gets one more column per poster size`() {
        val cols = (4..12).map { handheldGridColumns(it, 900, 144f, 8) }
        assertEquals(2, cols[0]); assertEquals(3, cols[1])
        assertEquals(900 / 144, handheldGridColumns(8, 900, 144f, 8))
        assertTrue(cols.zipWithNext().all { (a, b) -> b == a + 1 })
    }

    @Test fun `tiles never get narrower than 64 dp`() {
        assertEquals(900 / 64, handheldGridColumns(40, 900, 144f, 8))
        assertEquals(1, handheldGridColumns(4, 500, 144f, 8))
    }

    @Test fun `landscape artwork steps the same way`() {
        assertEquals(listOf(1, 2, 3, 4, 5), (2..6).map { handheldGridColumns(it, 900, 260f, 4) })
    }

    @Test fun `portrait phone keeps at least two poster columns and steps with density`() {
        val cols = (4..12).map { handheldPortraitColumns(it, 411, 144f, 8) }
        assertTrue(cols.all { it >= 2 })
        assertTrue(cols.last() > cols.first())
        assertEquals(3, handheldPortraitColumns(8, 411, 144f, 8))
    }

    @Test fun `portrait landscape artwork is no longer stuck at one column`() {
        assertEquals(3, handheldPortraitColumns(4, 411, 144f, 4))
        assertEquals(2, handheldPortraitColumns(4, 411, 260f, 4))
        assertEquals(1, handheldPortraitColumns(2, 411, 260f, 4))
    }
}
