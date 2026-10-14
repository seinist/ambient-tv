package com.ambient.tv.data.repository

import com.ambient.tv.data.remote.PlexApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlexPlaybackReportingTest {
    private data class Call(val name: String, val args: List<Any?>)
    private fun api(calls: MutableList<Call>, fail: (String) -> Unit = {}): PlexApiService =
        Proxy.newProxyInstance(PlexApiService::class.java.classLoader, arrayOf(PlexApiService::class.java)) { _, method, args ->
            calls += Call(method.name, args.orEmpty().toList())
            fail(method.name)
            Unit
        } as PlexApiService

    @Test fun heartbeatIncludesDurationAndAbsolutePosition() = runBlocking {
        val calls = mutableListOf<Call>()
        reportPlexPlayback(api(calls), "42", 125_000, "playing", 600_000)
        assertEquals(listOf("reportTimeline"), calls.map { it.name })
        assertEquals(listOf("42", "/library/metadata/42", 125_000L, "playing", 600_000L), calls.single().args.take(5))
    }

    @Test fun stopExplicitlyPersistsOffsetEvenWhenTimelineSucceeds() = runBlocking {
        val calls = mutableListOf<Call>()
        reportPlexPlayback(api(calls), "42", 125_000, "stopped", 600_000)
        assertEquals(listOf("reportTimeline", "reportProgress"), calls.map { it.name })
        assertEquals(listOf("42", 125_000L, "stopped"), calls.last().args.take(3))
    }

    @Test fun timelineFailureStillSavesProgress() = runBlocking {
        val calls = mutableListOf<Call>()
        reportPlexPlayback(api(calls) { if (it == "reportTimeline") throw IllegalStateException("server unavailable") }, "42", 125_000, "paused", null)
        assertEquals(listOf("reportTimeline", "reportProgress"), calls.map { it.name })
        assertNull(calls.first().args[4])
    }

    @Test fun failedFinalSaveIsNotReportedAsSuccess() = runBlocking {
        val calls = mutableListOf<Call>()
        try {
            reportPlexPlayback(api(calls) { if (it == "reportProgress") throw IllegalStateException("server unavailable") }, "42", 125_000, "stopped", 600_000)
            fail("Expected the persistence failure")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cancellationDoesNotStartFallbackRequest() = runBlocking {
        val calls = mutableListOf<Call>()
        try {
            reportPlexPlayback(api(calls) { throw CancellationException() }, "42", 125_000, "stopped", 600_000)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf("reportTimeline"), calls.map { it.name })
    }
}
