package com.ambient.tv.livetv

import com.ambient.tv.data.iptv.IptvSource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [34], application = android.app.Application::class)
class DeepAuditReproTest {
    private fun packets() = ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0 }

    @Test fun stoppingTimeshiftClosesTheUpstreamPromptly() {
        val server = ServerSocket(0)
        val peer = AtomicReference<Socket>()
        val closed = CountDownLatch(1)
        Thread {
            runCatching {
                server.accept().use { socket ->
                    peer.set(socket)
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                        write(packets()); flush()
                    }
                    if (input.read() == -1) closed.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val dir = kotlin.io.path.createTempDirectory("audit-stop").toFile()
        var buffer: TimeshiftBuffer? = null
        try {
            buffer = TimeshiftBuffer.start(OkHttpClient(), "http://127.0.0.1:${server.localPort}/live", emptyMap(), dir, 128L*1024*1024)
            assertNotNull(buffer)
            Thread.sleep(100)
            buffer!!.stop()
            assertTrue("Upstream remains connected after stop", closed.await(1500, TimeUnit.MILLISECONDS))
        } finally { buffer?.stop(); peer.get()?.close(); server.close(); dir.deleteRecursively() }
    }

    @Test fun cancellationDuringTimeshiftProbeClosesTheUnpublishedConnection() = runBlocking {
        val server = ServerSocket(0)
        val peer = AtomicReference<Socket>()
        val connected = CountDownLatch(1)
        val closed = CountDownLatch(1)
        Thread {
            runCatching {
                server.accept().use { socket ->
                    peer.set(socket)
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                        flush() // Deliberately never finish the MPEG-TS probe.
                    }
                    connected.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            LiveTimeshift.start(context, "test", "http://127.0.0.1:${server.localPort}/live", emptyMap(), 128L * 1024 * 1024)
        }
        try {
            assertTrue(connected.await(3, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue("Cancelled probe kept its upstream open", closed.await(1500, TimeUnit.MILLISECONDS))
            assertNull(LiveTimeshift.current)
        } finally { job.cancel(); LiveTimeshift.stop(); peer.get()?.close(); server.close() }
    }

    @Test fun timeshiftDoesNotForwardProviderTokensToAnotherHost() {
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = AtomicReference<String>()
        target.createContext("/live") { ex ->
            seen.set(ex.requestHeaders.getFirst("X-Plex-Token"))
            val bytes = packets()
            ex.responseHeaders.add("Content-Type", "video/mp2t")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        origin.createContext("/live") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:${target.address.port}/live")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        target.start(); origin.start()
        val dir = kotlin.io.path.createTempDirectory("audit-redirect").toFile()
        var buffer: TimeshiftBuffer? = null
        try {
            val clientField = LiveTimeshift::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = clientField.get(null) as OkHttpClient
            buffer = TimeshiftBuffer.start(client, "http://127.0.0.1:${origin.address.port}/live", mapOf("X-Plex-Token" to "synthetic-audit-token"), dir, 128L*1024*1024)
            assertNull("Foreign redirect received synthetic provider token", seen.get())
        } finally { buffer?.stop(); origin.stop(0); target.stop(0); dir.deleteRecursively() }
    }

    @Test fun changingGuideUrlUsesTheNewGuide() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val now = java.time.Instant.now()
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(java.time.ZoneOffset.UTC)
        fun guide(title: String) = "<tv><channel id=\"c\"><display-name>Channel</display-name></channel><programme channel=\"c\" start=\"${format.format(now.minusSeconds(60))}\" stop=\"${format.format(now.plusSeconds(3600))}\"><title>$title</title></programme></tv>"
        val docs = mapOf("/list" to "#EXTM3U\n#EXTINF:-1 tvg-id=\"c\",Channel\n$base/live\n", "/old" to guide("OLD"), "/new" to guide("NEW"))
        docs.forEach { (path, text) -> server.createContext(path) { ex ->
            val bytes = text.toByteArray(); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        } }
        server.start()
        val dir = kotlin.io.path.createTempDirectory("audit-guide").toFile()
        try {
            val config = IptvSource(id="same", name="Test", playlistUrl="$base/list", epgUrl="$base/old")
            fun repo(c: IptvSource) = IptvLiveTvRepository(LiveTvSource("iptv:same", "iptv", "Test"), c, OkHttpClient(), dir)
            assertEquals("OLD", repo(config).epgEvents(now.epochSecond, 1, false).single().title)
            assertEquals("NEW", repo(config.copy(epgUrl="$base/new")).epgEvents(now.epochSecond, 1, false).single().title)
        } finally { server.stop(0); dir.deleteRecursively() }
    }
}
