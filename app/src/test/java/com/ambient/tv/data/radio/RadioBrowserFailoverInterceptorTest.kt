package com.ambient.tv.data.radio

import com.ambient.tv.data.browse.InternetReachability
import com.ambient.tv.data.browse.OnlineServiceReports
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowserFailoverInterceptorTest {
    @Test fun cancellationBeforeExecutionDoesNotDiscoverOrContactMirrors() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        val pool = pool(discoveries)
        val reports = reports()
        val client = client(pool, reports) {
            attempts.incrementAndGet()
            error("A cancelled call must not reach a mirror")
        }
        val call = client.newCall(request())
        call.cancel()

        assertTrue(runCatching { call.execute() }.exceptionOrNull() is IOException)

        assertEquals(0, discoveries.get())
        assertEquals(0, attempts.get())
        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.reachable.isEmpty())
    }

    @Test fun cancellationDuringDiscoveryStopsBeforeTheFirstMirror() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        lateinit var call: Call
        val pool = RadioBrowserServerPool {
            discoveries.incrementAndGet()
            call.cancel()
            emptyList()
        }
        val reports = reports()
        val client = client(pool, reports) {
            attempts.incrementAndGet()
            error("Cancelled discovery must not start a mirror request")
        }
        call = client.newCall(request())

        assertTrue(runCatching { call.execute() }.exceptionOrNull() is IOException)

        assertEquals(1, discoveries.get())
        assertEquals(0, attempts.get())
        assertTrue(reports.state.value.failed.isEmpty())
        pool.hosts()
        assertEquals("Cancellation must not discard discovery", 1, discoveries.get())
    }

    @Test fun cancelledIoDoesNotRetryMarkOfflineOrDiscardTheWorkingMirror() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        val pool = pool(discoveries)
        pool.hosts()
        pool.markSuccessful(FALLBACK)
        val reports = reports()
        reports.succeeded(reports.begin("radio"))
        val client = client(pool, reports) { chain ->
            attempts.incrementAndGet()
            chain.call().cancel()
            throw IOException("Request cancelled by navigation")
        }

        assertTrue(runCatching { client.newCall(request()).execute() }.exceptionOrNull() is IOException)

        assertEquals(1, attempts.get())
        assertEquals(setOf("radio"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
        assertEquals(FALLBACK, pool.hosts().first())
        assertEquals(1, discoveries.get())
    }

    @Test fun cancellationAfterAResponseClosesItWithoutReportingOrRetrying() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        val pool = pool(discoveries)
        val reports = reports()
        val body = TrackedBody()
        val client = client(pool, reports) { chain ->
            attempts.incrementAndGet()
            chain.call().cancel()
            response(chain, 200, body)
        }

        assertTrue(runCatching { client.newCall(request()).execute() }.exceptionOrNull() is IOException)

        assertEquals(1, attempts.get())
        assertEquals(1, body.closes.get())
        assertTrue(reports.state.value.failed.isEmpty())
        assertTrue(reports.state.value.reachable.isEmpty())
        assertEquals(0L, pool.successGeneration)
        pool.hosts()
        assertEquals(1, discoveries.get())
    }

    @Test fun cancellationBetweenMirrorsDoesNotStartTheNextAttempt() {
        val attempts = AtomicInteger()
        val reports = reports()
        lateinit var body: TrackedBody
        val client = client(pool(), reports) { chain ->
            attempts.incrementAndGet()
            body = TrackedBody { chain.call().cancel() }
            response(chain, 503, body)
        }

        assertTrue(runCatching { client.newCall(request()).execute() }.exceptionOrNull() is IOException)

        assertEquals(1, attempts.get())
        assertEquals(1, body.closes.get())
        assertTrue(reports.state.value.failed.isEmpty())
    }

    @Test fun aRateLimitedMirrorPreventsALaterTransportFailureFromDeclaringRadioOffline() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        val pool = pool(discoveries)
        val reports = reports()
        val limitedBody = TrackedBody()
        val transportFailure = ConnectException("Second mirror unavailable")
        val client = client(pool, reports) { chain ->
            if (attempts.getAndIncrement() == 0) response(chain, 429, limitedBody)
            else throw transportFailure
        }

        val failure = runCatching { client.newCall(request()).execute() }.exceptionOrNull()

        assertSame(transportFailure, failure)
        assertEquals(2, attempts.get())
        assertEquals(1, limitedBody.closes.get())
        assertEquals(setOf("radio"), reports.state.value.reachable)
        assertTrue(reports.state.value.failed.isEmpty())
        assertEquals("Rate limiting does not prove a usable mirror", 0L, pool.successGeneration)
        pool.hosts()
        assertEquals(1, discoveries.get())
    }

    @Test fun finalHttpRejectionsClearStaleOfflineStatusWithoutPreferringThatMirror() {
        listOf(401, 403, 404, 429).forEach { status ->
            val discoveries = AtomicInteger()
            val attempts = AtomicInteger()
            val pool = pool(discoveries)
            val reports = reports()
            reports.unavailable(reports.begin("radio"))
            val firstBody = TrackedBody()
            val finalBody = TrackedBody()
            val client = client(pool, reports) { chain ->
                if (attempts.getAndIncrement() == 0) response(chain, 503, firstBody)
                else response(chain, status, finalBody)
            }

            client.newCall(request()).execute().use { result ->
                assertEquals(status, result.code)
                assertEquals(1, firstBody.closes.get())
                assertEquals("The caller owns the returned body", 0, finalBody.closes.get())
                assertEquals(setOf("radio"), reports.state.value.reachable)
                assertTrue(reports.state.value.failed.isEmpty())
                assertEquals(0L, pool.successGeneration)
            }

            assertEquals(1, finalBody.closes.get())
            assertEquals(2, attempts.get())
            assertEquals(RadioBrowserServerPool.DISCOVERY_HOST, pool.hosts().first())
            assertEquals(1, discoveries.get())
        }
    }

    @Test fun everyMirrorFailingWithIoMarksOnlyRadioOfflineAndExpiresDiscovery() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        val pool = pool(discoveries)
        val reports = reports()
        reports.succeeded(reports.begin("youtube"))
        val failure = ConnectException("Radio mirrors unreachable")
        val client = client(pool, reports) {
            attempts.incrementAndGet()
            throw failure
        }

        assertSame(failure, runCatching { client.newCall(request()).execute() }.exceptionOrNull())

        assertEquals(2, attempts.get())
        assertEquals(setOf("radio"), reports.state.value.failed)
        assertEquals(setOf("youtube"), reports.state.value.reachable)
        assertEquals(setOf("radio"), reports.state.value.unavailableServices(setOf("radio", "youtube", "plex")))
        pool.hosts()
        assertEquals(2, discoveries.get())
    }

    @Test fun everyMirrorReturningTransientServerErrorsReportsAnOutageAndClosesRetriedBodies() {
        listOf(408, 500, 503).forEach { status ->
            val discoveries = AtomicInteger()
            val pool = pool(discoveries)
            val reports = reports()
            val bodies = mutableListOf<TrackedBody>()
            val client = client(pool, reports) { chain ->
                val body = TrackedBody().also(bodies::add)
                response(chain, status, body)
            }

            client.newCall(request()).execute().use { result ->
                assertEquals(status, result.code)
                assertEquals(2, bodies.size)
                assertEquals(1, bodies.first().closes.get())
                assertEquals(0, bodies.last().closes.get())
                assertEquals(setOf("radio"), reports.state.value.failed)
                assertTrue(reports.state.value.reachable.isEmpty())
            }

            assertTrue(bodies.all { it.closes.get() == 1 })
            pool.hosts()
            assertEquals(2, discoveries.get())
        }
    }

    @Test fun cachedSuccessOrHttpRejectionCannotClearAnUnconfirmedOutage() {
        listOf(200, 403, 429).forEach { status ->
            val discoveries = AtomicInteger()
            val pool = pool(discoveries)
            val reports = reports()
            reports.unavailable(reports.begin("radio"))
            val before = reports.state.value
            val client = client(pool, reports) { chain -> response(chain, status, TrackedBody(), live = false) }

            client.newCall(request()).execute().use { assertEquals(status, it.code) }

            assertEquals(before, reports.state.value)
            assertEquals(0L, pool.successGeneration)
            pool.hosts()
            assertEquals(1, discoveries.get())
        }
    }

    @Test fun cachedServerErrorsCannotCreateAnOutageOrDiscardDiscovery() {
        val discoveries = AtomicInteger()
        val attempts = AtomicInteger()
        val pool = pool(discoveries)
        val reports = reports()
        val before = reports.state.value
        val client = client(pool, reports) { chain ->
            attempts.incrementAndGet()
            response(chain, 503, TrackedBody(), live = false)
        }

        client.newCall(request()).execute().use { assertEquals(503, it.code) }

        assertEquals(2, attempts.get())
        assertEquals(before, reports.state.value)
        pool.hosts()
        assertEquals(1, discoveries.get())
    }

    @Test fun aSuccessfulFallbackClosesTheFailedBodyAndBecomesPreferred() {
        val pool = pool()
        val reports = reports()
        val firstBody = TrackedBody()
        val finalBody = TrackedBody()
        val hosts = mutableListOf<String>()
        val client = client(pool, reports) { chain ->
            hosts += chain.request().url.host
            if (hosts.size == 1) response(chain, 503, firstBody)
            else response(chain, 200, finalBody)
        }

        client.newCall(request()).execute().use { result ->
            assertEquals(200, result.code)
            assertEquals(1, firstBody.closes.get())
            assertEquals(0, finalBody.closes.get())
        }

        assertEquals(1, finalBody.closes.get())
        assertEquals(listOf(RadioBrowserServerPool.DISCOVERY_HOST, FALLBACK), hosts)
        assertEquals(setOf("radio"), reports.state.value.reachable)
        assertFalse("radio" in reports.state.value.failed)
        assertEquals(FALLBACK, pool.hosts().first())
        assertEquals(1L, pool.successGeneration)
    }

    @Test(timeout = 10_000) fun anOlderFailedMirrorSequenceCannotUndoANewerSuccessfulFallback() {
        val discoveries = AtomicInteger()
        val pool = pool(discoveries)
        val reports = reports()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val oldHosts = CopyOnWriteArrayList<String>()
        val client = client(pool, reports) { chain ->
            if (chain.request().header("Fixture-Request") == "old") {
                oldHosts += chain.request().url.host
                if (oldHosts.size == 1) {
                    started.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                throw ConnectException("Older request failed")
            }
            response(chain,
                if (chain.request().url.host == FALLBACK) 200 else 503,
                TrackedBody())
        }
        try {
            val old = worker.submit<Throwable?> {
                runCatching {
                    client.newCall(request().newBuilder().header("Fixture-Request", "old").build())
                        .execute().close()
                }.exceptionOrNull()
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))

            client.newCall(request()).execute().use { assertEquals(200, it.code) }
            release.countDown()
            assertTrue(old.get(3, TimeUnit.SECONDS) is IOException)

            assertEquals(2, oldHosts.size)
            assertEquals(setOf("radio"), reports.state.value.reachable)
            assertTrue(reports.state.value.failed.isEmpty())
            assertEquals(FALLBACK, pool.hosts().first())
            assertEquals(1, discoveries.get())
            assertEquals(1L, pool.successGeneration)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    private fun reports() = OnlineServiceReports().apply {
        networkChanged("ethernet", InternetReachability.AVAILABLE)
    }

    private fun pool(discoveries: AtomicInteger = AtomicInteger()) = RadioBrowserServerPool {
        discoveries.incrementAndGet()
        emptyList()
    }

    private fun request() = Request.Builder()
        .url("https://${RadioBrowserServerPool.DISCOVERY_HOST}/json/stations/topclick/20")
        .build()

    private fun client(
        pool: RadioBrowserServerPool,
        reports: OnlineServiceReports,
        terminal: (Interceptor.Chain) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RadioBrowserFailoverInterceptor(pool, reports))
        .addInterceptor { chain -> terminal(chain) }
        .build()

    private fun response(chain: Interceptor.Chain, code: Int, body: TrackedBody, live: Boolean = true): Response {
        fun builder() = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Fixture response")
        return builder().body(body).apply {
            if (live) networkResponse(builder().build())
        }.build()
    }

    private class TrackedBody(onClose: () -> Unit = {}) : ResponseBody() {
        val closes = AtomicInteger()
        private val source = object : ForwardingSource(Buffer().writeUtf8("[]")) {
            override fun close() {
                closes.incrementAndGet()
                onClose()
                super.close()
            }
        }.buffer()
        override fun contentType(): MediaType = "application/json".toMediaType()
        override fun contentLength(): Long = 2L
        override fun source(): BufferedSource = source
    }

    companion object {
        private const val FALLBACK = "de1.api.radio-browser.info"
    }
}
