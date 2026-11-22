package com.ambient.tv.network

import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaArtworkNetworkInterceptorTest {
    private fun request() = ImageRequest.Builder(RuntimeEnvironment.getApplication())
        .data("http://media.test:32400/library/metadata/42/thumb")
        .diskCacheKey("account-scoped-image")
        .memoryCacheKey("visible-card")
        .memoryCacheKeyExtra("account", "account-a")

    private suspend fun intercepted(original: ImageRequest, blocked: Boolean): ImageRequest {
        class RecordingChain(override val request: ImageRequest) : Interceptor.Chain {
            override val size = Size.ORIGINAL
            override fun withRequest(request: ImageRequest): Interceptor.Chain = RecordingChain(request)
            override fun withSize(size: Size): Interceptor.Chain = this
            override suspend fun proceed(): ImageResult = ErrorResult(null, this.request, IllegalStateException("capture only"))
        }
        return MediaArtworkNetworkInterceptor { _, _ -> blocked }.intercept(RecordingChain(original)).request
    }

    @Test fun offlineAndRecoveryReuseTheSameActualCacheKeys() = runBlocking {
        val original = request().build()
        val offline = request().artworkNetworkAccess(ArtworkNetworkState(blocked = true)).build()
        val recovered = request().artworkNetworkAccess(ArtworkNetworkState(recovery = 1L)).build()
        assertNotEquals(offline.memoryCacheKeyExtras, recovered.memoryCacheKeyExtras)
        val cached = intercepted(offline, blocked = true)
        val online = intercepted(recovered, blocked = false)
        for (actual in listOf(cached, online)) {
            assertEquals(original.diskCacheKey, actual.diskCacheKey)
            assertEquals(original.memoryCacheKey, actual.memoryCacheKey)
            assertEquals(original.memoryCacheKeyExtras, actual.memoryCacheKeyExtras)
            assertEquals(CachePolicy.ENABLED, actual.memoryCachePolicy)
            assertEquals(CachePolicy.ENABLED, actual.diskCachePolicy)
        }
        assertEquals(CachePolicy.DISABLED, cached.networkCachePolicy)
        assertEquals(CachePolicy.ENABLED, online.networkCachePolicy)
    }

    @Test fun lockedDetailRequestStaysCacheOnlyEvenIfHostIsReachable() = runBlocking {
        val locked = request().artworkNetworkAccess(ArtworkNetworkState(), cacheOnly = true).build()
        val actual = intercepted(locked, blocked = false)
        assertEquals(CachePolicy.DISABLED, actual.networkCachePolicy)
        assertFalse(MEDIA_ARTWORK_NETWORK_EXTRA in actual.memoryCacheKeyExtras)
        assertEquals(CachePolicy.ENABLED, actual.diskCachePolicy)
    }

    @Test fun backgroundRequestsAlsoRespectAnOutage() = runBlocking {
        val actual = intercepted(request().build(), blocked = true)
        assertEquals(CachePolicy.DISABLED, actual.networkCachePolicy)
        assertEquals("account-scoped-image", actual.diskCacheKey)
    }
}
