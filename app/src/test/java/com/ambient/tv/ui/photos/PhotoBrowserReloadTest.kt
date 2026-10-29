package com.ambient.tv.ui.photos

import androidx.lifecycle.ViewModelStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.data.photos.PhotoEntry
import com.ambient.tv.data.photos.PhotoKind
import com.ambient.tv.data.photos.PhotoSort
import com.ambient.tv.data.photos.PhotoViewerSession
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
class PhotoBrowserReloadTest {
    private fun photo(id: String) = PhotoEntry(id, id, PhotoKind.PHOTO, image = { _, _ -> null })

    @Test fun manualReloadKeepsAlbumSortFocusAndItemsAndRejectsDuplicateClicks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<Result<List<PhotoEntry>>>()
        var requests = 0
        val model = PhotoBrowserViewModel(PhotoViewerSession(), AppPreferences(memoryPreferences()),
            listAlbum = {
                requests++
                if (requests == 1) Result.success(listOf(photo("a"), photo("b"))) else pending.await()
            }, listTimeline = { Result.success(emptyList()) })
        val store = ViewModelStore().apply { put("browser", model) }
        try {
            model.enter("album", "Album")
            runCurrent()
            model.setSort(PhotoSort.NAME_DESC)
            model.rememberFocus("a")
            model.reload()
            model.reload()
            assertTrue(model.state.value.refreshing)
            assertFalse(model.state.value.loading)
            runCurrent()
            assertEquals(2, requests)
            assertEquals(listOf("b", "a"), model.state.value.entries.map { it.id })
            assertEquals("album", model.state.value.current?.key)
            assertEquals("a", model.state.value.focusId)
            pending.complete(Result.success(listOf(photo("a"), photo("c"))))
            runCurrent()
            assertFalse(model.state.value.refreshing)
            assertEquals(PhotoSort.NAME_DESC, model.state.value.sort)
            assertEquals(listOf("c", "a"), model.state.value.entries.map { it.id })
            assertEquals("a", model.state.value.focusId)
        } finally {
            pending.complete(Result.success(emptyList()))
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun cancelledReloadCannotPublishOrClearNewAlbumBusyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stale = CompletableDeferred<Result<List<PhotoEntry>>>()
        val child = CompletableDeferred<Result<List<PhotoEntry>>>()
        var albumRequests = 0
        val model = PhotoBrowserViewModel(PhotoViewerSession(), AppPreferences(memoryPreferences()),
            listAlbum = { key ->
                if (key == "child") child.await()
                else if (++albumRequests == 1) Result.success(listOf(photo("a")))
                else withContext(NonCancellable) { stale.await() }
            }, listTimeline = { Result.success(emptyList()) })
        val store = ViewModelStore().apply { put("browser", model) }
        try {
            model.enter("album", "Album")
            runCurrent()
            model.reload()
            runCurrent()
            model.openFolder(PhotoEntry("child", "Child", PhotoKind.FOLDER, image = { _, _ -> null }))
            runCurrent()
            stale.complete(Result.success(listOf(photo("stale"))))
            runCurrent()
            assertEquals("child", model.state.value.current?.key)
            assertTrue(model.state.value.refreshing)
            assertTrue(model.state.value.entries.none { it.id == "stale" })
            child.complete(Result.success(listOf(photo("fresh"))))
            runCurrent()
            assertFalse(model.state.value.refreshing)
            assertEquals(listOf("fresh"), model.state.value.entries.map { it.id })
        } finally {
            stale.complete(Result.success(emptyList()))
            child.complete(Result.success(emptyList()))
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun manualFailurePreservesContentAndNextReloadClearsFeedback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var requests = 0
        val model = PhotoBrowserViewModel(PhotoViewerSession(), AppPreferences(memoryPreferences()),
            listAlbum = {
                when (++requests) {
                    2 -> Result.failure(IllegalStateException("Offline"))
                    else -> Result.success(listOf(photo("a")))
                }
            }, listTimeline = { Result.success(emptyList()) })
        val store = ViewModelStore().apply { put("browser", model) }
        try {
            model.enter("album", "Album")
            runCurrent()
            model.reload()
            runCurrent()
            assertFalse(model.state.value.refreshing)
            assertFalse(model.state.value.failed)
            assertTrue(model.state.value.refreshFailed)
            assertEquals(listOf("a"), model.state.value.entries.map { it.id })
            model.reload()
            assertFalse(model.state.value.refreshFailed)
            runCurrent()
            assertFalse(model.state.value.refreshing)
            assertFalse(model.state.value.refreshFailed)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun leavingTimelineCancelsItsReloadWithoutReplacingAlbumOrTimelineData() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stale = CompletableDeferred<Result<List<PhotoEntry>>>()
        var timelineRequests = 0
        val model = PhotoBrowserViewModel(PhotoViewerSession(), AppPreferences(memoryPreferences()),
            listAlbum = { Result.success(listOf(photo("album-photo"))) },
            listTimeline = {
                if (++timelineRequests == 1) Result.success(listOf(photo("timeline-photo")))
                else withContext(NonCancellable) { stale.await() }
            })
        val store = ViewModelStore().apply { put("browser", model) }
        try {
            model.enter("album", "Album")
            runCurrent()
            model.setSort(PhotoSort.NAME_DESC)
            model.toggleTimeline()
            runCurrent()
            model.reload()
            runCurrent()
            assertTrue(model.back())
            assertFalse(model.state.value.refreshing)
            assertFalse(model.state.value.timeline)
            stale.complete(Result.success(listOf(photo("stale"))))
            runCurrent()
            assertEquals(PhotoSort.NAME_DESC, model.state.value.sort)
            assertEquals(listOf("album-photo"), model.state.value.entries.map { it.id })
            assertEquals(listOf("timeline-photo"), model.state.value.timelineGroups.flatMap { it.entries }.map { it.id })
        } finally {
            stale.complete(Result.success(emptyList()))
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
