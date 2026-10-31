package com.ambient.tv.ui.radio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioViewportPagingTest {
    @Test fun trailingSkeletonsAndEmptyViewportNeverMasqueradeAsStations() {
        assertEquals(-1, radioLastVisibleStationIndex(emptyList(), 48))
        assertEquals(-1, radioLastVisibleStationIndex(listOf(48, 49), 48))
        assertEquals(47, radioLastVisibleStationIndex(listOf(45, 47, 48, 49), 48))
        assertEquals(12, radioLastVisibleStationIndex(listOf(12), 48))
        assertEquals(-1, radioLastVisibleStationIndex(listOf(0, 1), 0))
    }

    @Test fun paginationOnlyTriggersForARealNearEndStation() {
        val last = radioLastVisibleStationIndex(listOf(41, 45, 48), 48)
        assertTrue(radioShouldLoadOnFocus(last, 48, hasMore = true, loading = false, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(17, 48, hasMore = true, loading = false, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(last, 48, hasMore = false, loading = false, loadingMore = false))
        assertFalse(radioShouldLoadOnFocus(last, 48, hasMore = true, loading = false, loadingMore = true))
    }

    @Test fun sameViewportObserverHandlesTvAndHandheldForGridAndList() {
        val code = File("src/main/java/com/ambient/tv/ui/radio/RadioViewportPaging.kt").readText()
        assertFalse(code.contains("LocalHandheldUi"))
        assertTrue(code.contains("mode == RadioViewMode.GRID"))
        assertTrue(code.contains("listState.layoutInfo.visibleItemsInfo"))
    }

    @Test fun loadMoreClaimsItsOffsetBeforeSchedulingTheCoroutine() {
        val code = File("src/main/java/com/ambient/tv/ui/radio/RadioViewModel.kt").readText()
        val loadMore = code.substringAfter("    fun loadMore() {").substringBefore("    fun onViewportNearEnd(")
        assertTrue(loadMore.contains("copy(loadingMore = true)"))
        assertTrue(loadMore.indexOf("copy(loadingMore = true)") < loadMore.indexOf("loadStations(reset = false)"))
    }
}
