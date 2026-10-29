package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPaginationTest {
    @Test fun focusLoadsOnlyNearTheGridEnd() {
        assertFalse(radioShouldLoadOnFocus(index = 10, size = 48, hasMore = true, loading = false, loadingMore = false))
        assertTrue(radioShouldLoadOnFocus(index = 38, size = 48, hasMore = true, loading = false, loadingMore = false))
        assertTrue(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = true, loading = false, loadingMore = false))
    }

    @Test fun focusDoesNotDuplicateRequestsOrLoadWhenFinished() {
        assertFalse(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = false, loading = false, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = true, loading = true, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(index = 47, size = 48, hasMore = true, loading = false, loadingMore = true))
        assertFalse(radioShouldLoadOnFocus(index = -1, size = 48, hasMore = true, loading = false, loadingMore = false))
    }

    @Test fun pagingMatchesSmartTubeGridTuning() {
        assertEquals(10, RADIO_GRID_CONTINUE_THRESHOLD)
        assertEquals(350L, RADIO_FOCUS_SETTLE_MS)
        assertEquals(2, RADIO_GRID_END_PLACEHOLDERS)
        assertEquals("radio-grid-more:1", radioGridEndPlaceholderKey(1))
    }
}
