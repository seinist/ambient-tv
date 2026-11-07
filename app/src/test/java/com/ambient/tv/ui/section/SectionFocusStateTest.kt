package com.ambient.tv.ui.section

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ambient.tv.connectivity.HostConnectivity
import com.ambient.tv.data.browse.BrowseScopes
import com.ambient.tv.data.browse.MetadataPrefetcher
import com.ambient.tv.data.browse.SourceAvailability
import com.ambient.tv.data.dlna.DlnaStore
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.data.local.memoryPreferences
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import com.ambient.tv.domain.repository.MediaRepository
import com.ambient.tv.ui.common.PlexRefreshBus
import com.ambient.tv.wol.WakeMachines
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SectionFocusStateTest {
    @Test fun libraryDpadBurstDoesNotPublishScreenStateAndNavigationCapturesTheLatestItem() = runTest {
        withFixture(SectionPage.LIBRARY) { fixture ->
            val model = fixture.model
            val initial = model.uiState.value
            val emissions = mutableListOf<SectionUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.uiState.collect { emissions += it }
            }

            repeat(120) { index ->
                model.onItemFocused(fixture.items[index % fixture.items.size])
                assertSame("A focus hop must not invalidate the entire library", initial, model.uiState.value)
            }
            runCurrent()
            assertEquals(1, emissions.size)
            assertNull(fixture.preferences.sectionFocus(LIBRARY_STATE_KEY))

            val latest = fixture.items.last().ratingKey
            model.captureFocusForReturn()
            assertEquals(latest, (model.uiState.value as SectionUiState.Loaded).focusedRatingKey)
            assertEquals(latest, fixture.preferences.sectionFocus(LIBRARY_STATE_KEY))
            assertNull(fixture.preferences.sectionFocusRow(LIBRARY_STATE_KEY))
            assertEquals("Leaving publishes one complete restore snapshot", 2, emissions.size)

            model.captureFocusForReturn()
            assertEquals("A second navigation/disposal capture is idempotent", 2, emissions.size)
        }
    }

    @Test fun collectionDpadBurstKeepsScreenStateStableAndCapturesTheCollectionAddress() = runTest {
        withFixture(SectionPage.COLLECTIONS) { fixture ->
            val model = fixture.model
            val initial = model.uiState.value as SectionUiState.Loaded
            val emissions = mutableListOf<SectionUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.uiState.collect { emissions += it }
            }

            repeat(80) { index ->
                model.onCollectionFocused(initial.collections[index % initial.collections.size].id)
                assertSame("Collection posters share the same focus-only fast path", initial, model.uiState.value)
            }
            runCurrent()
            assertEquals(1, emissions.size)
            val latest = initial.collections.last().id

            model.captureFocusForReturn()
            val captured = model.uiState.value as SectionUiState.Loaded
            assertEquals(latest, captured.focusedCollectionId)
            assertNull(captured.focusedRatingKey)
            assertNull(captured.focusedRecommendationRowId)
            assertEquals("collection:$latest", fixture.preferences.sectionFocus(COLLECTION_STATE_KEY))
            assertEquals(2, emissions.size)
        }
    }

    @Test fun sortFilterAndViewRebuildsUseTheLatestBufferedFocus() = runTest {
        withFixture(SectionPage.LIBRARY) { fixture ->
            val model = fixture.model
            model.onItemFocused(fixture.items[15])
            model.setSort(SectionSort.YEAR)
            assertEquals(fixture.items[15].ratingKey, model.awaitLoaded { it.sort == SectionSort.YEAR }.focusedRatingKey)

            model.onItemFocused(fixture.items[31])
            model.setFilter(SectionFilter.UNWATCHED)
            assertEquals(fixture.items[31].ratingKey, model.awaitLoaded { it.filter == SectionFilter.UNWATCHED }.focusedRatingKey)

            model.onItemFocused(fixture.items[7])
            model.setViewMode(SectionViewMode.POSTER_INFO_LIST)
            assertEquals(fixture.items[7].ratingKey,
                model.awaitLoaded { it.viewMode == SectionViewMode.POSTER_INFO_LIST }.focusedRatingKey)

            model.onItemFocused(fixture.items[23])
            model.setGridColumns(12)
            assertEquals(fixture.items[23].ratingKey, model.awaitLoaded { it.gridColumns == 12 }.focusedRatingKey)
        }
    }

    @Test fun switchingPagesPreservesIndependentLibraryAndCollectionFocus() = runTest {
        withFixture(SectionPage.LIBRARY) { fixture ->
            val model = fixture.model
            val libraryTarget = fixture.items[27].ratingKey
            model.onItemFocused(fixture.items[27])
            model.selectPage(SectionPage.COLLECTIONS)
            val collections = model.awaitLoaded { it.page == SectionPage.COLLECTIONS }
            val collectionTarget = collections.collections.last().id
            model.onCollectionFocused(collectionTarget)

            model.selectPage(SectionPage.LIBRARY)
            val library = model.awaitLoaded { it.page == SectionPage.LIBRARY }
            assertEquals(libraryTarget, library.focusedRatingKey)
            assertNull(library.focusedCollectionId)

            model.selectPage(SectionPage.COLLECTIONS)
            val restored = model.awaitLoaded { it.page == SectionPage.COLLECTIONS }
            assertEquals(collectionTarget, restored.focusedCollectionId)
            assertNull(restored.focusedRatingKey)
            assertEquals(libraryTarget, fixture.preferences.sectionFocus(LIBRARY_STATE_KEY))
            assertEquals("collection:$collectionTarget", fixture.preferences.sectionFocus(COLLECTION_STATE_KEY))
        }
    }

    @Test fun captureDuringAPendingPageRebuildKeepsThePublishedPagesOwnFocus() = runTest {
        withFixture(SectionPage.LIBRARY) { fixture ->
            val model = fixture.model
            val libraryTarget = fixture.items[27].ratingKey
            model.onItemFocused(fixture.items[27])
            model.selectPage(SectionPage.COLLECTIONS)
            val collectionTarget = model.awaitLoaded { it.page == SectionPage.COLLECTIONS }.collections.last().id
            model.onCollectionFocused(collectionTarget)
            model.selectPage(SectionPage.LIBRARY)
            model.awaitLoaded { it.page == SectionPage.LIBRARY }

            // Block at the repository boundary, before the worker can publish the new page.
            // The ViewModel's requested page is now Collections while uiState is still Library.
            val toCollections = fixture.pauseNextRebuild()
            try {
                model.selectPage(SectionPage.COLLECTIONS)
                toCollections.awaitStarted()
                model.captureFocusForReturn()
                val stillLibrary = model.uiState.value as SectionUiState.Loaded
                assertEquals(SectionPage.LIBRARY, stillLibrary.page)
                assertEquals(libraryTarget, stillLibrary.focusedRatingKey)
                assertNull(stillLibrary.focusedCollectionId)
            } finally {
                toCollections.release()
            }
            model.awaitLoaded { it.page == SectionPage.COLLECTIONS }

            val toLibrary = fixture.pauseNextRebuild()
            try {
                model.selectPage(SectionPage.LIBRARY)
                toLibrary.awaitStarted()
                model.captureFocusForReturn()
                val stillCollections = model.uiState.value as SectionUiState.Loaded
                assertEquals(SectionPage.COLLECTIONS, stillCollections.page)
                assertEquals(collectionTarget, stillCollections.focusedCollectionId)
                assertNull(stillCollections.focusedRatingKey)
            } finally {
                toLibrary.release()
            }
            assertEquals(libraryTarget, model.awaitLoaded { it.page == SectionPage.LIBRARY }.focusedRatingKey)
        }
    }

    private suspend fun TestScope.withFixture(page: SectionPage, block: suspend TestScope.(Fixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        try {
            fixture = Fixture(page)
            fixture.model.awaitLoaded { it.page == page }
            block(fixture)
        } finally {
            fixture?.close()
            Dispatchers.resetMain()
        }
    }

    // Rebuild deliberately runs on Dispatchers.Default in production. Wait on a real-time
    // dispatcher so runTest cannot advance a virtual timeout before that worker publishes.
    private suspend fun SectionViewModel.awaitLoaded(
        predicate: (SectionUiState.Loaded) -> Boolean,
    ): SectionUiState.Loaded = withContext(Dispatchers.Default) {
        withTimeout(10_000) { uiState.filterIsInstance<SectionUiState.Loaded>().first(predicate) }
    }

    private class Fixture(page: SectionPage) : AutoCloseable {
        val preferences = AppPreferences(memoryPreferences()).apply {
            setSectionPage(SECTION_KEY, page.name)
        }
        val items = List(40) { index ->
            MediaItem(
                ratingKey = "movie-$index", title = "Film ${index.toString().padStart(2, '0')}",
                type = "movie", index = null, thumbUrl = null, artUrl = null,
                year = 1980 + index, summary = null, tagline = null, durationMs = null,
                viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
                grandparentTitle = null, grandparentRatingKey = null, contentRating = null,
                rating = null, studio = null,
                collections = listOf(MediaTag("group-${index % 4}", "Group ${index % 4}")),
            )
        }
        private val library = LibrarySection(SECTION_KEY, "Films", "movie", null, null)
        private val nextRebuildGate = AtomicReference<RebuildGate?>()
        private val rebuildGates = mutableListOf<RebuildGate>()
        private val repository = Proxy.newProxyInstance(
            MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java),
        ) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "cachedSectionItems" -> items
                "cachedProviderOnDeck", "cachedOnDeck" -> emptyList<MediaItem>()
                "cachedLibrarySection" -> {
                    nextRebuildGate.getAndSet(null)?.awaitRelease()
                    library
                }
                "cachedLibrarySections" -> listOf(library)
                "getMetadataFast" -> items.firstOrNull { it.ratingKey == args?.firstOrNull() }
                else -> error("Unexpected repository call in focus-only fixture: ${method.name}")
            }
        } as MediaRepository
        private val context: Application = RuntimeEnvironment.getApplication()
        private val scopes = BrowseScopes(preferences, TokenStore(memoryPreferences(), preferences))
        private val dlna = DlnaStore(memoryPreferences())
        private val hosts = HostConnectivity(preferences, dlna, WakeMachines(preferences, dlna))
        private val availability = SourceAvailability(context, scopes, preferences, hosts)
        private val prefetcher = MetadataPrefetcher(repository, availability, scopes)
        val model = SectionViewModel(context, SavedStateHandle(mapOf("sectionKey" to SECTION_KEY)),
            repository, prefetcher, availability, preferences, PlexRefreshBus())
        private val store = ViewModelStore().apply { put("section", model) }

        fun pauseNextRebuild(): RebuildGate = RebuildGate().also {
            rebuildGates += it
            nextRebuildGate.set(it)
        }

        override fun close() {
            rebuildGates.forEach { it.release() }
            store.clear()
            // This app singleton has no shutdown API. Release its dedicated executor after
            // the fixture so each test cannot leave a non-daemon prefetch worker behind.
            val scope = MetadataPrefetcher::class.java.getDeclaredField("scope")
                .apply { isAccessible = true }.get(prefetcher) as CoroutineScope
            scope.cancel()
            (scope.coroutineContext[ContinuationInterceptor] as? ExecutorCoroutineDispatcher)?.close()
        }
    }

    private class RebuildGate {
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun awaitRelease() {
            started.countDown()
            check(released.await(10, TimeUnit.SECONDS)) { "Test did not release the pending rebuild" }
        }

        suspend fun awaitStarted() = withContext(Dispatchers.IO) {
            assertTrue("The requested rebuild must reach its repository read", started.await(10, TimeUnit.SECONDS))
        }

        fun release() { released.countDown() }
    }

    private companion object {
        const val SECTION_KEY = "test-films"
        const val LIBRARY_STATE_KEY = "$SECTION_KEY:library"
        const val COLLECTION_STATE_KEY = "$SECTION_KEY:collections"
    }
}
