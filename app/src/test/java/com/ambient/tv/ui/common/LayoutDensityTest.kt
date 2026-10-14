package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutDensityTest {
    @Test
    fun landscapeDensityDisplaysActualVisibleColumns() {
        assertEquals(listOf(2, 2, 3, 3, 4, 4, 5, 5, 6),
            (4..12).map { displayedGridColumns(it, ViewPreview.LANDSCAPE) })
    }

    @Test
    fun eachLandscapeStepperActionChangesOneVisibleColumn() {
        val values = generateSequence(4) { current ->
            changedGridColumns(current, ViewPreview.LANDSCAPE, 1).takeIf { it != current }
        }.toList()

        assertEquals(listOf(4, 6, 8, 10, 12), values)
        assertEquals(listOf(2, 3, 4, 5, 6), values.map {
            displayedGridColumns(it, ViewPreview.LANDSCAPE)
        })
    }

    @Test
    fun posterDensityRetainsSingleColumnSteps() {
        assertEquals(7, changedGridColumns(8, ViewPreview.POSTER, -1))
        assertEquals(9, changedGridColumns(8, ViewPreview.POSTER, 1))
        assertEquals(8, displayedGridColumns(8, ViewPreview.POSTER))
    }
}
