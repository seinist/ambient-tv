package com.ambient.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarBackPolicyTest {
    @Test fun rootMenuAlwaysReturnsToCurrentContent() {
        assertEquals(SidebarBackAction.RETURN_TO_CONTENT, sidebarBackAction(false, false))
    }

    @Test fun submenuReturnsToParentBeforeClosingMenu() {
        assertEquals(SidebarBackAction.LEAVE_NESTED_LEVEL, sidebarBackAction(false, true))
        assertEquals(SidebarBackAction.RETURN_TO_CONTENT, sidebarBackAction(false, false))
    }

    @Test fun miniPlayerLeavesItsOwnRegionFirst() {
        assertEquals(SidebarBackAction.FOCUS_SETTINGS, sidebarBackAction(true, true))
        assertEquals(SidebarBackAction.FOCUS_SETTINGS, sidebarBackAction(true, false))
    }
}
