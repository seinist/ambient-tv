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

    @Test fun portraitPhoneStatusIsNarrowerThanTheAvailableScreen() {
        val metrics = handheldFeedbackPageMetrics(tablet = false, landscape = false)
        assertTrue(metrics.maxWidthDp + 2 * metrics.gutterDp < 360)
        assertTrue(metrics.contentPaddingDp < 20)
        assertTrue(metrics.iconFrameDp < 64)
        assertTrue(metrics.iconDp < 34)
    }

    @Test fun landscapePhoneAndTabletCardsStayBounded() {
        val landscape = handheldFeedbackPageMetrics(tablet = false, landscape = true)
        val tablet = handheldFeedbackPageMetrics(tablet = true, landscape = false)
        assertTrue(landscape.maxWidthDp + 2 * landscape.gutterDp < 640)
        assertTrue(tablet.maxWidthDp + 2 * tablet.gutterDp < 600)
        assertTrue(tablet.maxWidthDp > landscape.maxWidthDp)
    }
}
