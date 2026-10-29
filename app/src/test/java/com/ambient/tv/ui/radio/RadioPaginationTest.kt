package com.ambient.tv.ui.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPaginationTest {
    @Test fun loadsOnlyNearTheEnd() {
        assertFalse(radioShouldLoadMore(lastVisibleIndex = 10, totalItemCount = 48, hasMore = true, loading = false, loadingMore = false))
        assertTrue(radioShouldLoadMore(lastVisibleIndex = 40, totalItemCount = 48, hasMore = true, loading = false, loadingMore = false))
    }

    @Test fun doesNotDuplicateRequestsOrLoadWhenFinished() {
        assertFalse(radioShouldLoadMore(lastVisibleIndex = 47, totalItemCount = 48, hasMore = false, loading = false, loadingMore = false))
        assertFalse(radioShouldLoadMore(lastVisibleIndex = 47, totalItemCount = 48, hasMore = true, loading = true, loadingMore = false))
        assertFalse(radioShouldLoadMore(lastVisibleIndex = 47, totalItemCount = 48, hasMore = true, loading = false, loadingMore = true))
    }

    @Test fun shortFirstPageCanFillItselfAutomatically() {
        assertTrue(radioShouldLoadMore(lastVisibleIndex = 5, totalItemCount = 6, hasMore = true, loading = false, loadingMore = false))
    }
}
