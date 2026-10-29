package com.ambient.tv.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.domain.model.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilteredBrowseRefreshTest {
    private fun saved() = SavedStateHandle(mapOf("kind" to "collection", "filterId" to "44", "title" to "Collection"))
    private fun item(key: String, year: Int = 2020) = MediaItem(
        ratingKey = key, title = key, type = "movie", index = null, thumbUrl = null, artUrl = null,
        year = year, summary = null, tagline = null, durationMs = null, viewOffsetMs = null,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null,
    )

    @Test fun manualRefreshPreparesOnlyItsSourceAndKeepsContentAndPreferencesWhileBusy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prepared = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Result<List<MediaItem>>>()
        val sources = mutableListOf<Set<String>>()
        var reads = 0
        val old = item("old")
        val model = FilteredBrowseViewModel(saved(), AppPreferences(memoryPreferences()),
            { kind, id ->
                assertEquals("collection", kind)
                assertEquals("44", id)
                if (++reads == 1) Result.success(listOf(old)) else response.await()
            },
            { sources += it; prepared.await() },
        )
        val store = ViewModelStore().apply { put("browse", model) }
        try {
            model.load()
            runCurrent()
            model.onItemFocused(old)
            model.setViewMode(BrowseViewMode.LIST)
            model.setSort(BrowseSort.YEAR)
            val before = model.uiState.value

            model.refresh()
            model.refresh()
            assertTrue(model.refreshing.value)
            runCurrent()
            assertEquals(listOf(setOf("plex")), sources)
            assertEquals("The read waits for cache preparation", 1, reads)
            assertEquals(before, model.uiState.value)

            prepared.complete(Unit)
            runCurrent()
            assertEquals(2, reads)
            assertEquals(before, model.uiState.value)
            response.complete(Result.success(listOf(old.copy(title = "Updated"), item("new", 2026))))
            runCurrent()
            val loaded = model.uiState.value as FilteredBrowseUiState.Loaded
            assertEquals(listOf("new", "old"), loaded.items.map { it.ratingKey })
            assertEquals(BrowseViewMode.LIST, loaded.viewMode)
            assertEquals(BrowseSort.YEAR, loaded.sort)
            assertTrue(loaded.sortDescending)
            assertEquals("old", loaded.focusedRatingKey)
            assertFalse(model.refreshing.value)
            assertNull(model.refreshError.value)
        } finally {
            prepared.complete(Unit)
            response.complete(Result.success(emptyList()))
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun aLateCanceledReadCannotPublishOrClearTheNewRequestsBusyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val late = CompletableDeferred<Result<List<MediaItem>>>()
        val fresh = CompletableDeferred<Result<List<MediaItem>>>()
        var reads = 0
        val base = item("base")
        val model = FilteredBrowseViewModel(saved(), AppPreferences(memoryPreferences()),
            { _, _ -> when (++reads) {
                1 -> Result.success(listOf(base))
                2 -> withContext(NonCancellable) { late.await() }
                else -> fresh.await()
            } },
            {},
        )
        val store = ViewModelStore().apply { put("browse", model) }
        try {
            model.load()
            runCurrent()
            model.refresh()
            runCurrent()
            model.load() // A new destination-entry/reconnect read supersedes the manual request.
            runCurrent()
            late.complete(Result.success(listOf(item("stale"))))
            runCurrent()
            assertTrue("An old finally block must not clear the current spinner", model.refreshing.value)
            assertEquals(listOf(base), (model.uiState.value as FilteredBrowseUiState.Loaded).items)

            fresh.complete(Result.success(listOf(item("fresh"))))
            runCurrent()
            assertEquals("fresh", (model.uiState.value as FilteredBrowseUiState.Loaded).items.single().ratingKey)
            assertFalse(model.refreshing.value)
        } finally {
            late.complete(Result.success(emptyList()))
            fresh.complete(Result.success(emptyList()))
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun failedCachePreparationLeavesLoadedItemsVisibleAndAllowsAnotherRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var preparations = 0
        var reads = 0
        val base = item("base")
        val model = FilteredBrowseViewModel(saved(), AppPreferences(memoryPreferences()),
            { _, _ -> reads++; Result.success(listOf(base)) },
            { preparations++; error("Cache preparation failed") },
        )
        val store = ViewModelStore().apply { put("browse", model) }
        try {
            model.load()
            runCurrent()
            repeat(2) {
                model.refresh()
                runCurrent()
                assertEquals(listOf(base), (model.uiState.value as FilteredBrowseUiState.Loaded).items)
                assertFalse(model.refreshing.value)
                assertNotNull(model.refreshError.value)
            }
            assertEquals(2, preparations)
            assertEquals(1, reads)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
