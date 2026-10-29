package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class FilterFocusPolicyTest {
    @Test fun initialEntryCanRestoreContentButEditingAFilterConsumesThatPermission() {
        val initial = FilterFocusPolicy()
        assertTrue(initial.allowContentAutoFocus)
        val editing = initial.edited("filter:genre")
        assertFalse(editing.allowContentAutoFocus)
        assertEquals("filter:genre", editing.lastFilter)
    }

    @Test fun resetReturnsToTheFilterThatWasEditedInsteadOfTheFirstFilterOrAResult() {
        val afterReset = FilterFocusPolicy().edited("filter:quality").edited("filter:decade")
            .restoreFilter(FilterFocusSurface.BAR)
        assertFalse(afterReset.allowContentAutoFocus)
        assertEquals(FilterFocusTarget("filter:decade", FilterFocusSurface.BAR), afterReset.pending)
    }

    @Test fun ChangingSortDoesNotForgetWhichFilterCausedTheEmptyResult() {
        val afterReset = FilterFocusPolicy().edited("filter:genre").edited("sort").edited("sort-order")
            .restoreFilter(FilterFocusSurface.BAR)
        assertEquals("filter:genre", afterReset.pending?.key)
    }

    @Test fun hiddenBarReturnsToTheSameFieldInTheReopenedPanel() {
        val afterReset = FilterFocusPolicy().edited("filter:quality").restoreFilter(FilterFocusSurface.PANEL)
        assertEquals(FilterFocusTarget("filter:quality", FilterFocusSurface.PANEL), afterReset.pending)
    }

    @Test fun deliberateContentEntryCancelsAPendingReturnAndPreservesFilterHistory() {
        val state = FilterFocusPolicy().edited("filter:genre").restoreFilter(FilterFocusSurface.BAR).enterContent()
        assertTrue(state.allowContentAutoFocus)
        assertNull(state.pending)
        assertEquals("filter:genre", state.lastFilter)
    }

    @Test fun editingAnotherControlSupersedesAnOlderResetReturn() {
        val state = FilterFocusPolicy().edited("filter:quality").restoreFilter(FilterFocusSurface.BAR)
            .edited("filter:genre")
        assertFalse(state.allowContentAutoFocus)
        assertNull(state.pending)
        assertEquals("filter:genre", state.lastFilter)
    }

    @Test fun aReopenedPanelWaitsForItsOwnControlsInsteadOfUsingTheOldBar() {
        val bar = FilterFocusTarget("filter:genre", FilterFocusSurface.BAR)
        val panel = bar.copy(surface = FilterFocusSurface.PANEL)
        assertNull(filterFocusReturnTarget(panel, listOf(bar)))
        assertEquals(panel, filterFocusReturnTarget(panel, listOf(bar, panel)))
    }

    @Test fun aRemovedFacetFallsBackToAnAvailableFilterBeforeSort() {
        val sort = FilterFocusTarget("sort", FilterFocusSurface.BAR)
        val filter = FilterFocusTarget("filter:status", FilterFocusSurface.BAR)
        val missing = FilterFocusTarget("filter:genre", FilterFocusSurface.BAR)
        assertEquals(filter, filterFocusReturnTarget(missing, listOf(sort, filter)))
        assertEquals(sort, filterFocusReturnTarget(missing, listOf(sort)))
    }
}
