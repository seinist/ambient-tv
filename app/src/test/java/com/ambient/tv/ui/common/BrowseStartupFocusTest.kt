package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseStartupFocusTest {
    @Test fun automaticFocusDoesNotConsumeInitialFollowFirst() {
        val gate = BrowseStartupFocusGate()
        assertTrue(gate.followsFirst())
        // The focus callback itself never calls claim(), so cached data can still be replaced.
        assertTrue(gate.followsFirst())
        gate.claim()
        assertFalse(gate.followsFirst())
    }

    @Test fun explicitPlayerReturnAlwaysTakesPriority() {
        val gate = BrowseStartupFocusGate()
        assertFalse(gate.followsFirst(explicitReturn = true))
        assertTrue(gate.followsFirst())
        gate.claim()
        assertFalse(gate.followsFirst())
    }

    @Test fun focusMemoryPersistsOnlyForTheSameProcessLocalBrowseScope() {
        val first = BrowseStartupFocusSessions.get("startup-focus-test-1")
        val different = BrowseStartupFocusSessions.get("startup-focus-test-2")
        assertSame(first, BrowseStartupFocusSessions.get("startup-focus-test-1"))
        assertFalse(first === different)
        first.claim()
        assertFalse(BrowseStartupFocusSessions.get("startup-focus-test-1").followsFirst())
        assertTrue(different.followsFirst())
    }
}
