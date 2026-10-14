package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDropUpSelectorTest {
    @Test
    fun navigationSkipsUnavailableOptionsInBothDirectionsAndWraps() {
        val keys = listOf("auto", "gpu", "vulkan", "direct")
        assertEquals(3, nextEnabledSelectorIndex(1, keys, setOf("vulkan"), 1))
        assertEquals(1, nextEnabledSelectorIndex(3, keys, setOf("vulkan"), -1))
        assertEquals(0, nextEnabledSelectorIndex(3, keys, setOf("vulkan"), 1))
        assertEquals(null, nextEnabledSelectorIndex(0, keys, keys.toSet(), 1))
        assertEquals(null, nextEnabledSelectorIndex(0, emptyList(), emptySet(), 1))
    }

    @Test
    fun wrapSelectorIndexMovesDownAndWraps() {
        assertEquals(1, wrapSelectorIndex(0, 3, 1))
        assertEquals(2, wrapSelectorIndex(1, 3, 1))
        assertEquals(0, wrapSelectorIndex(2, 3, 1))
    }

    @Test
    fun wrapSelectorIndexMovesUpAndWraps() {
        assertEquals(2, wrapSelectorIndex(0, 3, -1))
        assertEquals(0, wrapSelectorIndex(1, 3, -1))
        assertEquals(1, wrapSelectorIndex(2, 3, -1))
    }

    @Test
    fun wrapSelectorIndexHandlesEmptyAndSingle() {
        assertEquals(0, wrapSelectorIndex(0, 0, 1))
        assertEquals(0, wrapSelectorIndex(0, 1, 1))
        assertEquals(0, wrapSelectorIndex(0, 1, -1))
    }

    @Test
    fun televisionMenusMatchTheClosedControlWidth() {
        assertEquals(true, selectorMenuMatchesTrigger(handheld = false))
        assertEquals(false, selectorMenuMatchesTrigger(handheld = true))
    }

    @Test
    fun televisionMenuOpensBelowTheControlWhenItFits() {
        assertEquals(308, selectMenuTop(anchorTop = 200, anchorBottom = 300, windowHeight = 1080, popupHeight = 400, gap = 8, margin = 32))
    }

    @Test
    fun televisionMenuFlipsAboveWhenOnlyThatSideFits() {
        assertEquals(392, selectMenuTop(anchorTop = 800, anchorBottom = 900, windowHeight = 1080, popupHeight = 400, gap = 8, margin = 32))
    }

    @Test
    fun televisionMenuHeightIsCappedToTheRoomierSide() {
        assertEquals(740, selectMenuMaxHeight(anchorTop = 200, anchorBottom = 300, windowHeight = 1080, gap = 8, margin = 32))
        assertEquals(760, selectMenuMaxHeight(anchorTop = 800, anchorBottom = 900, windowHeight = 1080, gap = 8, margin = 32))
    }

    @Test
    fun cappedTelevisionMenuStaysInsideTheScreenMargin() {
        val height = selectMenuMaxHeight(anchorTop = 500, anchorBottom = 600, windowHeight = 1080, gap = 8, margin = 32)
        assertEquals(460, height)
        assertEquals(32, selectMenuTop(anchorTop = 500, anchorBottom = 600, windowHeight = 1080, popupHeight = height, gap = 8, margin = 32))
    }
}
