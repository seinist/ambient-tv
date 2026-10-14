package com.ambient.tv.livetv

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LiveTvSessionCleanupTest {
    private fun http(requests: MutableList<String>, respond: (String) -> Int) = LiveTvHttp(
        "http://localhost/".toHttpUrl(), emptyMap(),
        OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            requests.add(path)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(respond(path)).message("test").body("{}".toResponseBody()).build()
        }.build(),
    ) { false }

    @Test fun reportingTimeoutStillReleasesEveryResource() = runBlocking {
        for (browser in listOf(false, true)) {
            val requests = CopyOnWriteArrayList<String>()
            val http = http(requests) { path ->
                if (path.endsWith("Stopped") || path.endsWith("timeline")) Thread.sleep(250)
                200
            }
            val session: LiveTvPlaybackSession = if (browser) BrowserSession(http, "item", "media", "session", "live", "device")
                else PlexTvSession(http, "/livetv/sessions/live", "session")
            val result = runCatching { withTimeout(100) { session.report(0, false, true) } }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
            if (browser) {
                assertTrue(requests.contains("/LiveStreams/Close"))
                assertTrue(requests.contains("/Videos/ActiveEncodings"))
            } else assertTrue(requests.contains("/video/:/transcode/universal/stop"))
        }
    }

    @Test fun failedReleaseRemainsRetryableAndDoesNotBlockOtherEndpoints() = runBlocking {
        for (browser in listOf(false, true)) {
            val requests = CopyOnWriteArrayList<String>()
            var failures = 1
            val http = http(requests) { path ->
                if ((path.endsWith("Close") || path.endsWith("stop")) && failures-- > 0) 503 else 200
            }
            val session: LiveTvPlaybackSession = if (browser) BrowserSession(http, "item", "media", "session", "live", "device")
                else PlexTvSession(http, "/livetv/sessions/live", "session")
            session.report(0, false, true)
            if (browser) assertTrue(requests.contains("/Videos/ActiveEncodings"))
            session.report(0, false, true)
            val size = requests.size
            assertEquals(2, requests.count { it.endsWith(if (browser) "Close" else "stop") })
            session.report(0, false, true)
            assertEquals(size, requests.size)
        }
    }
}
