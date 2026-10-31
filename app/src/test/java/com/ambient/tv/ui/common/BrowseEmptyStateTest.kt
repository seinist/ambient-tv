package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseEmptyStateTest {
    @Test fun initialAndCachedEmptyRequestsRemainPendingUntilTheirOwnResponse() {
        assertTrue(awaitingEmptyBrowseResult(isEmpty = true, loading = true, refreshing = false))
        assertTrue(awaitingEmptyBrowseResult(isEmpty = true, loading = false, refreshing = true))
        assertFalse(awaitingEmptyBrowseResult(isEmpty = true, loading = false, refreshing = false))
    }

    @Test fun populatedCacheCanStayOnScreenDuringRefresh() {
        assertFalse(awaitingEmptyBrowseResult(isEmpty = false, loading = false, refreshing = true))
        assertFalse(awaitingEmptyBrowseResult(isEmpty = false, loading = false, refreshing = false))
    }
}
