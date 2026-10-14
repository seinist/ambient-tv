package com.ambient.tv.ui.photos

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.data.photos.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerLifecycleTest {
    private fun photo(id: String) = PhotoEntry(id, id, PhotoKind.PHOTO, image = { _, _ -> null })

    @Test fun disablingSubfoldersRejectsEvenANonCooperativeLateResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<Result<List<PhotoEntry>>>()
        val base = photo("base")
        val session = PhotoViewerSession().apply { request = PhotoViewerSession.Request("Album", listOf(base), 0, false, "album") }
        val model = PhotoViewerViewModel(session, AppPreferences(memoryPreferences()),
            { withContext(NonCancellable) { pending.await() } }, { Result.success(null) })
        val store = androidx.lifecycle.ViewModelStore().apply { put("viewer", model) }
        try {
            model.setIncludeSubfolders(true, "album")
            runCurrent()
            assertTrue(model.state.value.loadingSubfolders)
            model.setIncludeSubfolders(false, "album")
            pending.complete(Result.success(listOf(photo("stale"))))
            runCurrent()
            assertEquals(listOf(base), model.state.value.entries)
            assertFalse(model.state.value.loadingSubfolders)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun stoppingSlideshowDuringScanKeepsItStoppedAfterCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<Result<List<PhotoEntry>>>()
        val session = PhotoViewerSession().apply { request = PhotoViewerSession.Request("Album", listOf(photo("base")), 0, false, "album") }
        val model = PhotoViewerViewModel(session, AppPreferences(memoryPreferences()), { pending.await() }, { Result.success(null) })
        val store = androidx.lifecycle.ViewModelStore().apply { put("viewer", model) }
        try {
            model.expandSubfolders("album", startSlideshow = true)
            runCurrent()
            assertTrue(model.state.value.slideshow)
            model.stopSlideshow()
            pending.complete(Result.success(listOf(photo("nested"))))
            runCurrent()
            assertEquals("nested", model.state.value.current?.id)
            assertFalse(model.state.value.slideshow)
            advanceTimeBy(10_000)
            assertFalse(model.state.value.slideshow)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
