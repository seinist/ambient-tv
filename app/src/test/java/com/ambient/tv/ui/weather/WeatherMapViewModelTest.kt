package com.ambient.tv.ui.weather

import androidx.lifecycle.ViewModelStore
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.FeedbackDelivery
import com.ambient.tv.notifications.FeedbackPhase
import com.ambient.tv.notifications.OperationFeedbackReporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherMapViewModelTest {
    private val location = WeatherLocation("erfurt", "Erfurt", "Deutschland", latitude = 50.98, longitude = 11.03, timezone = "Europe/Berlin")
    private val request = WeatherMapRequest(location, WeatherMapKind.RADAR, WeatherMapProvider.RAINVIEWER,
        WeatherMapViewport.at(location.latitude, location.longitude), networkAllowed = true)

    @Test fun onlyTheExplicitAttemptForcesMetadataAfterResumeAndPan() = runTest {
        fixture { model, loader, notices, _ ->
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            model.dismiss(request)
            model.present(request, true)
            runCurrent()
            model.present(request.copy(viewport = request.viewport.pan(1, 0)), true)
            runCurrent()
            assertEquals(listOf(false, true, false, false), loader.calls.map { it.force })
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun aVisibleManualFailureStaysInline() = runTest {
        fixture { model, loader, notices, _ ->
            loader.response = { if (it.force) WeatherMapResult(error = WeatherMapError.UNAVAILABLE) else success(it.provider) }
            model.feedback.visibility(Any(), request.scope)
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            assertEquals(WeatherMapError.UNAVAILABLE, model.state.value.result?.error)
            assertFalse(model.state.value.loading)
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun aManualFailureCompletesOnceAfterItsPageIsHidden() = runTest {
        fixture { model, loader, notices, _ ->
            val pending = CompletableDeferred<WeatherMapResult>()
            loader.response = { if (it.force) pending.await() else success(it.provider) }
            val owner = Any()
            model.feedback.visibility(owner, request.scope)
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            model.feedback.visibility(owner, null)
            model.dismiss(request)
            assertTrue(model.state.value.loading)
            pending.complete(WeatherMapResult(error = WeatherMapError.RATE_LIMITED))
            runCurrent()
            assertFalse(model.state.value.loading)
            assertEquals(WeatherMapError.RATE_LIMITED, model.state.value.result?.error)
            assertEquals(1, notices.size)
            assertEquals("RainViewer · Erfurt", notices.single().subject)
            runCurrent()
            assertEquals(1, notices.size)
        }
    }

    @Test fun aSuccessfulHiddenRefreshNeverPostsASuccessNotice() = runTest {
        fixture { model, loader, notices, _ ->
            val pending = CompletableDeferred<WeatherMapResult>()
            loader.response = { if (it.force) pending.await() else success(it.provider) }
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            model.dismiss(request)
            pending.complete(success(WeatherMapProvider.RAINVIEWER))
            runCurrent()
            assertFalse(model.state.value.loading)
            assertNull(model.state.value.result?.error)
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun hiddenAutomaticWorkIsCancelledAndCannotPublishALateFailure() = runTest {
        fixture { model, loader, notices, _ ->
            val pending = CompletableDeferred<WeatherMapResult>()
            loader.response = { withContext(NonCancellable) { pending.await() } }
            model.present(request, true)
            runCurrent()
            model.dismiss(request)
            assertFalse(model.state.value.loading)
            pending.complete(WeatherMapResult(error = WeatherMapError.OFFLINE))
            runCurrent()
            assertNull(model.state.value.result?.error)
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun oldProviderOrViewportCannotOverwriteTheCurrentMapOrNotify() = runTest {
        for (next in listOf(request.copy(provider = WeatherMapProvider.DWD),
            request.copy(viewport = request.viewport.pan(1, 0)))) {
            fixture { model, loader, notices, selection ->
                val pending = CompletableDeferred<WeatherMapResult>()
                loader.response = { if (it.force) withContext(NonCancellable) { pending.await() } else success(it.provider) }
                model.present(request, true)
                runCurrent()
                model.refresh()
                runCurrent()
                model.present(next, true)
                // The composition and preferences observer may receive the choice in either order.
                selection.value = selection.value.copy(radarProvider = next.provider!!.id)
                runCurrent()
                pending.complete(WeatherMapResult(error = WeatherMapError.UNAVAILABLE))
                runCurrent()
                assertEquals(next, model.state.value.request)
                assertEquals(next.provider.id, model.state.value.result?.series?.providerId)
                assertNull(model.state.value.result?.error)
                assertTrue(notices.isEmpty())
            }
        }
    }

    @Test fun switchingBetweenEnabledProvidersKeepsTheActiveMapAuthorized() = runTest {
        fixture { model, loader, notices, selection ->
            selection.value = selection.value.copy(radarProvider = "rainviewer,dwd")
            runCurrent()
            model.present(request, true)
            runCurrent()
            val dwd = request.copy(provider = WeatherMapProvider.DWD)
            model.present(dwd, true)
            runCurrent()
            assertEquals(listOf(WeatherMapProvider.RAINVIEWER, WeatherMapProvider.DWD),
                loader.calls.map { it.provider })
            assertEquals(dwd, model.state.value.request)
            assertNotNull(model.state.value.result)

            selection.value = selection.value.copy(radarProvider = "dwd")
            runCurrent()
            assertEquals(dwd, model.state.value.request)
            assertNotNull(model.state.value.result)
            assertEquals(2, loader.calls.size)
            assertTrue(notices.isEmpty())

            selection.value = selection.value.copy(radarProvider = "rainviewer")
            runCurrent()
            assertNull(model.state.value.result)
        }
    }

    @Test fun disablingOnlineAccessAlsoCancelsAHiddenManualRequest() = runTest {
        fixture { model, loader, notices, policy ->
            val pending = CompletableDeferred<WeatherMapResult>()
            loader.response = { if (it.force) withContext(NonCancellable) { pending.await() } else success(it.provider) }
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            model.dismiss(request)
            policy.value = policy.value.copy(networkAllowed = false)
            runCurrent()
            assertFalse(model.state.value.loading)
            pending.complete(WeatherMapResult(error = WeatherMapError.UNAVAILABLE))
            runCurrent()
            assertEquals(WeatherMapError.NETWORK_DISABLED, model.state.value.result?.error)
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun changingTheSelectedPlaceOrProviderCancelsAHiddenManualAttempt() = runTest {
        val choices = listOf(
            policy().copy(radarProvider = "dwd"),
            policy().copy(location = location.copy(id = "other")),
            policy().copy(location = location.copy(latitude = 51.0)),
            policy().copy(location = location.copy(longitude = 11.1)),
        )
        for (choice in choices) fixture { model, loader, notices, selection ->
            val pending = CompletableDeferred<WeatherMapResult>()
            loader.response = { if (it.force) withContext(NonCancellable) { pending.await() } else success(it.provider) }
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            model.dismiss(request)
            selection.value = choice
            runCurrent()
            assertFalse(model.state.value.loading)
            pending.complete(WeatherMapResult(error = WeatherMapError.UNAVAILABLE))
            runCurrent()
            assertNull(model.state.value.result)
            assertTrue(notices.isEmpty())
            assertEquals(2, loader.calls.size) // A hidden selection change never starts another load.
        }
    }

    @Test fun changingTheOtherMapCategoryDoesNotCancelTheUsersRefresh() = runTest {
        fixture { model, loader, notices, selection ->
            val pending = CompletableDeferred<WeatherMapResult>()
            loader.response = { if (it.force) pending.await() else success(it.provider) }
            model.present(request, true)
            runCurrent()
            model.refresh()
            runCurrent()
            model.dismiss(request)
            selection.value = selection.value.copy(satelliteProvider = "eumetsat")
            runCurrent()
            assertTrue(model.state.value.loading)
            pending.complete(WeatherMapResult(error = WeatherMapError.UNAVAILABLE))
            runCurrent()
            assertEquals(1, notices.size)
        }
    }

    @Test fun restoringNetworkAccessClearsTheKnownPolicyErrorWithoutAHiddenLoad() = runTest {
        fixture { model, loader, notices, selection ->
            model.present(request, true)
            runCurrent()
            model.dismiss(request)
            selection.value = selection.value.copy(networkAllowed = false)
            runCurrent()
            assertEquals(WeatherMapError.NETWORK_DISABLED, model.state.value.result?.error)
            selection.value = selection.value.copy(networkAllowed = true)
            runCurrent()
            assertNull(model.state.value.result?.error)
            assertEquals(WeatherMapProvider.RAINVIEWER.id, model.state.value.result?.series?.providerId)
            assertEquals(1, loader.calls.size)
            assertTrue(notices.isEmpty())
        }
    }

    @Test fun automaticAndInformationalFeedbackNeverBecomesAGlobalNotice() {
        WeatherMapError.entries.forEach { error ->
            assertEquals(FeedbackDelivery.INLINE, weatherMapFeedback(error, request).delivery)
        }
        listOf(WeatherMapError.NETWORK_DISABLED, WeatherMapError.NO_COVERAGE).forEach { error ->
            val message = weatherMapFeedback(error, request, manual = true)
            assertEquals(FeedbackPhase.INFO, message.phase)
            assertFalse(message.shouldNotify(false))
        }
        assertEquals(FeedbackPhase.WARNING, weatherMapFeedback(WeatherMapError.RATE_LIMITED, request).phase)
        assertEquals(FeedbackPhase.ERROR, weatherMapFeedback(WeatherMapError.OFFLINE, request).phase)
        assertEquals(FeedbackPhase.ERROR, weatherMapFeedback(WeatherMapError.UNAVAILABLE, request).phase)
        assertEquals(FeedbackPhase.WARNING, weatherMapLayerFeedback(request, coverage = false).phase)
        assertFalse(weatherMapLayerFeedback(request, coverage = true).shouldNotify(false))
    }

    private fun policy() = WeatherMapPolicy(true, location, "rainviewer", "")

    private suspend fun TestScope.fixture(block: suspend (WeatherMapViewModel, FakeLoader, MutableList<AppNotice>, MutableStateFlow<WeatherMapPolicy>) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val loader = FakeLoader()
        val notices = mutableListOf<AppNotice>()
        val policy = MutableStateFlow(policy())
        val model = WeatherMapViewModel(loader, OperationFeedbackReporter { notices += it }, policy)
        val store = ViewModelStore().apply { put("maps", model) }
        try { block(model, loader, notices, policy) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private data class Call(val provider: WeatherMapProvider, val viewport: WeatherMapViewport, val allowed: Boolean, val force: Boolean)
    private inner class FakeLoader : WeatherMapLoader {
        val calls = mutableListOf<Call>()
        var response: suspend (Call) -> WeatherMapResult = { success(it.provider) }
        override suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport) = WeatherMapResult()
        override suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport, networkAllowed: Boolean, force: Boolean): WeatherMapResult {
            val call = Call(provider, viewport, networkAllowed, force)
            calls += call
            return response(call)
        }
        override suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport, series: WeatherMapSeries, index: Int, networkAllowed: Boolean) =
            WeatherMapResult(error = WeatherMapError.UNAVAILABLE)
    }

    private fun success(provider: WeatherMapProvider) = WeatherMapResult(series = WeatherMapSeries(provider.id,
        listOf(WeatherMapFrame("2026-11-15T15:00:00Z", 1_794_754_800_000)), 1_794_755_000_000))
}
