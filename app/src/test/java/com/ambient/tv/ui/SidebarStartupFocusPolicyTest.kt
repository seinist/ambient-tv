package com.ambient.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarStartupFocusPolicyTest {
    @Test fun coldStartNeverAutoOpensSidebarWhileHomeIsLoading() {
        assertFalse(shouldAutoFocusSidebarAfterContentFailure(
            hasFocusedBrowseContent = false, waitsForContent = true,
        ))
    }

    @Test fun readinessWithoutActualItemFocusMustStillKeepTheMenuCollapsed() {
        // A destination may report ready before its first tile owns focus on a fast TV.
        assertFalse(shouldAutoFocusSidebarAfterContentFailure(
            hasFocusedBrowseContent = false, waitsForContent = true,
        ))
    }

    @Test fun aLaterFocusLossMayUseTheExistingMenuRecovery() {
        assertTrue(shouldAutoFocusSidebarAfterContentFailure(
            hasFocusedBrowseContent = true, waitsForContent = true,
        ))
    }

    @Test fun nonLoadingDestinationKeepsTheExistingFallback() {
        assertTrue(shouldAutoFocusSidebarAfterContentFailure(
            hasFocusedBrowseContent = false, waitsForContent = false,
        ))
    }
}
