package com.ambient.tv.ui.weather

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.data.weather.WeatherMapFrame
import com.ambient.tv.data.weather.WeatherMapImage
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherMapResult
import com.ambient.tv.data.weather.WeatherMapSeries
import com.ambient.tv.data.weather.WeatherMapViewport
import com.ambient.tv.notifications.OperationFeedbackReporter
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses real bitmaps and a controlled transport to cover animation timing and lifecycle races. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherMapPlaybackTest {
    private val location = WeatherLocation("erfurt", "Erfurt", "Deutschland", latitude = 50.98,
        longitude = 11.03, timezone = "Europe/Berlin")
    private val request = WeatherMapRequest(location, WeatherMapKind.RADAR, WeatherMapProvider.RAINVIEWER,
        WeatherMapViewport.at(location.latitude, location.longitude), networkAllowed = true)
    private val frames = (0..3).map {
        val time = Instant.parse("2026-11-15T15:00:00Z").plusSeconds(it * 600L)
        WeatherMapFrame(time.toString(), time.toEpochMilli())
    }

    @Test fun openingAMapDoesNotPrefetchAndPlayStartsLoadingImmediatelyWithBoundedLookahead() = runTest {
        fixture { model, loader, _ ->
            model.present(request, true)
            runCurrent()
            assertEquals(3, model.state.value.selectedFrame)
            assertTrue(loader.frameCalls.isEmpty())
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            loader.frameResponse = { call ->
                if (call.index == 0) first.await()
                if (call.index == 1) second.await()
                loader.image(call.provider, call.index)
            }
            model.togglePlayback()
            runCurrent()
            assertEquals(listOf(0, 1), loader.frameCalls.map { it.index })
            assertEquals(3, model.state.value.selectedFrame)
            assertTrue(model.state.value.frameLoading)
            first.complete(Unit)
            runCurrent()
            // No 1.1-second timer has to elapse before the first animation frame appears.
            assertEquals(0, model.state.value.selectedFrame)
            assertEquals(frames[0], model.state.value.result?.image?.frame)
            assertEquals(listOf(0, 1, 2), loader.frameCalls.map { it.index })
            assertFalse(model.state.value.frameLoading)
            second.complete(Unit)
        }
    }

    @Test fun repeatedLoopsReuseImagesAndKeepTimestampAndSelectedFrameInSync() = runTest {
        fixture { model, loader, _ ->
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            assertEquals(0, model.state.value.selectedFrame)
            repeat(10) { step ->
                advanceTimeBy(1100)
                runCurrent()
                val expected = (step + 1) % frames.size
                assertEquals(expected, model.state.value.selectedFrame)
                assertEquals(frames[expected], model.state.value.result?.image?.frame)
            }
            assertEquals(listOf(0, 1, 2), loader.frameCalls.map { it.index })
        }
    }

    @Test fun aSlowFrameKeepsTheLastImageVisibleAndDoesNotSkipAhead() = runTest {
        fixture { model, loader, _ ->
            val waiting = CompletableDeferred<Unit>()
            loader.frameResponse = { call ->
                if (call.index == 1) waiting.await()
                loader.image(call.provider, call.index)
            }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(0, model.state.value.selectedFrame)
            assertEquals(frames[0], model.state.value.result?.image?.frame)
            assertTrue(model.state.value.playing)
            assertTrue(model.state.value.frameLoading)
            waiting.complete(Unit)
            runCurrent()
            assertEquals(1, model.state.value.selectedFrame)
            assertFalse(model.state.value.frameLoading)
            advanceTimeBy(1100)
            runCurrent()
            assertEquals(2, model.state.value.selectedFrame)
        }
    }

    @Test fun pauseCancelsPendingFramesAndRemainsUsableWhileBuffering() = runTest {
        fixture { model, loader, _ ->
            loader.frameResponse = { CompletableDeferred<WeatherMapResult>().await() }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            assertTrue(model.state.value.frameLoading)
            model.togglePlayback()
            runCurrent()
            assertFalse(model.state.value.playing)
            assertFalse(model.state.value.frameLoading)
            assertEquals(setOf(0, 1), loader.cancelled.toSet())
            assertEquals(3, model.state.value.selectedFrame)
            val calls = loader.frameCalls.size
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(calls, loader.frameCalls.size)
        }
    }

    @Test fun aHiddenMapRejectsLateAnimationFrames() = runTest {
        fixture { model, loader, _ ->
            val waiting = CompletableDeferred<Unit>()
            loader.frameResponse = { call ->
                withContext(NonCancellable) { waiting.await() }
                loader.image(call.provider, call.index)
            }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            model.dismiss(request)
            waiting.complete(Unit)
            runCurrent()
            assertFalse(model.state.value.playing)
            assertFalse(model.state.value.frameLoading)
            assertEquals(3, model.state.value.selectedFrame)
            assertEquals(frames[3], model.state.value.result?.image?.frame)
        }
    }

    @Test fun changingTheViewportCannotReceiveOldBufferedFrames() = runTest {
        fixture { model, loader, _ ->
            val waiting = CompletableDeferred<Unit>()
            loader.frameResponse = { call ->
                withContext(NonCancellable) { waiting.await() }
                loader.image(call.provider, call.index)
            }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            val next = request.copy(viewport = request.viewport.pan(1, 0))
            model.present(next, true)
            runCurrent()
            waiting.complete(Unit)
            runCurrent()
            assertEquals(next, model.state.value.request)
            assertEquals(3, model.state.value.selectedFrame)
            assertFalse(model.state.value.playing)
            assertNull(model.state.value.result?.error)
        }
    }

    @Test fun aFailedFrameStopsAnimationWithoutLosingTheLastGoodImage() = runTest {
        fixture { model, loader, _ ->
            loader.frameResponse = { call ->
                if (call.index == 1) WeatherMapResult(error = WeatherMapError.RATE_LIMITED)
                else loader.image(call.provider, call.index)
            }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            advanceTimeBy(1100)
            runCurrent()
            assertFalse(model.state.value.playing)
            assertFalse(model.state.value.frameLoading)
            assertEquals(0, model.state.value.selectedFrame)
            assertEquals(frames[0], model.state.value.result?.image?.frame)
            assertEquals(WeatherMapError.RATE_LIMITED, model.state.value.result?.error)
        }
    }

    @Test fun manualSteppingCancelsAnimationAndRejectsItsLateFrames() = runTest {
        fixture { model, loader, _ ->
            val waiting = CompletableDeferred<Unit>()
            loader.frameResponse = { call ->
                if (call.index < 2) withContext(NonCancellable) { waiting.await() }
                loader.image(call.provider, call.index)
            }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            model.step(-1)
            runCurrent()
            assertEquals(2, model.state.value.selectedFrame)
            waiting.complete(Unit)
            runCurrent()
            assertEquals(2, model.state.value.selectedFrame)
            assertFalse(model.state.value.playing)
            assertFalse(model.state.value.frameLoading)
        }
    }

    @Test fun disablingWeatherCancelsAheadRequestsWithoutRestartingThem() = runTest {
        fixture { model, loader, policy ->
            loader.frameResponse = { CompletableDeferred<WeatherMapResult>().await() }
            model.present(request, true)
            runCurrent()
            model.togglePlayback()
            runCurrent()
            policy.value = policy.value.copy(networkAllowed = false)
            runCurrent()
            assertEquals(setOf(0, 1), loader.cancelled.toSet())
            assertFalse(model.state.value.playing)
            assertEquals(WeatherMapError.NETWORK_DISABLED, model.state.value.result?.error)
            assertEquals(2, loader.frameCalls.size)
        }
    }

    private suspend fun TestScope.fixture(block: suspend (WeatherMapViewModel, FakeLoader,
        MutableStateFlow<WeatherMapPolicy>) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val loader = FakeLoader()
        val policy = MutableStateFlow(WeatherMapPolicy(true, location, "rainviewer", ""))
        val model = WeatherMapViewModel(loader, OperationFeedbackReporter { fail("Automatic playback must stay inline") }, policy)
        val store = ViewModelStore().apply { put("maps", model) }
        try { block(model, loader, policy) }
        finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private data class FrameCall(val provider: WeatherMapProvider, val viewport: WeatherMapViewport, val index: Int)

    private inner class FakeLoader : WeatherMapLoader {
        val frameCalls = mutableListOf<FrameCall>()
        val cancelled = mutableListOf<Int>()
        var frameResponse: suspend (FrameCall) -> WeatherMapResult = { image(it.provider, it.index) }
        private val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        fun image(provider: WeatherMapProvider, index: Int) = WeatherMapResult(
            WeatherMapSeries(provider.id, frames, frames.last().epochMillis),
            WeatherMapImage(bitmap, frames[index], fromCache = false, baseMissing = false, coverageMissing = false),
        )
        override suspend fun cached(provider: WeatherMapProvider, viewport: WeatherMapViewport) = WeatherMapResult()
        override suspend fun load(provider: WeatherMapProvider, viewport: WeatherMapViewport,
            networkAllowed: Boolean, force: Boolean) = image(provider, frames.lastIndex)
        override suspend fun frame(provider: WeatherMapProvider, viewport: WeatherMapViewport,
            series: WeatherMapSeries, index: Int, networkAllowed: Boolean): WeatherMapResult {
            val call = FrameCall(provider, viewport, index)
            frameCalls += call
            try { return frameResponse(call) }
            finally { if (!currentCoroutineContext().isActive) cancelled += index }
        }
    }
}
