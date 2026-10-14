package com.ambient.tv.data.jellyfin

import java.lang.reflect.Proxy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JellyfinPlaybackSegmentsTest {
    private fun api(response: () -> JellyfinSegmentsResponse): JellyfinApiService =
        Proxy.newProxyInstance(JellyfinApiService::class.java.classLoader, arrayOf(JellyfinApiService::class.java)) { _, method, args ->
            check(method.name == "getMediaSegments")
            assertEquals("episode", args[0])
            response()
        } as JellyfinApiService

    @Test fun nativeIntroAndOutroUseTheOriginalTimeline() = runBlocking {
        val segments = loadJellyfinPlaybackSegments(api { JellyfinSegmentsResponse(listOf(
            JellyfinSegment("Intro", 120_000_000, 620_000_000),
            JellyfinSegment("Outro", 900_000_000, 950_000_000))) }, "episode")
        assertEquals(listOf("intro", "credits"), segments.map { it.type })
        assertEquals(listOf(12_000L, 90_000L), segments.map { it.startMs })
        assertEquals(listOf(62_000L, 95_000L), segments.map { it.endMs })
    }
    @Test fun unavailableEndpointIsOptional() = runBlocking {
        assertTrue(loadJellyfinPlaybackSegments(api { throw IOException("unavailable endpoint") }, "episode").isEmpty())
    }
    @Test fun cancellationIsNeverSwallowed() = runBlocking {
        try {
            loadJellyfinPlaybackSegments(api { throw CancellationException("leaving player") }, "episode")
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
}
