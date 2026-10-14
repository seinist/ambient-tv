package com.ambient.tv.network

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedirectGuardTest {
    /** Minimal HTTP/1.1 server on 127.0.0.1 that records request headers and answers [reply]. */
    private class Server(private val reply: () -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val requests = CopyOnWriteArrayList<List<String>>()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val head = generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                        // OkHttp connects before network interceptors run; a connection that
                        // never carried a request is not a request.
                        if (head.isNotEmpty()) {
                            requests += head
                            it.getOutputStream().write(reply().toByteArray(Charsets.ISO_8859_1))
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private val servers = mutableListOf<Server>()
    private fun server(reply: () -> String) = Server(reply).also { servers += it }

    @After
    fun close() = servers.forEach { it.close() }

    private val client = CredentialRedirectGuard.install(OkHttpClient.Builder()).build()

    private fun redirectTo(location: String, code: Int = 302) = { "HTTP/1.1 $code Moved\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }
    private val ok = { "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }

    private fun List<String>.has(header: String) = any { it.startsWith("$header:", ignoreCase = true) }

    @Test
    fun `tokens do not follow a redirect to another host`() {
        val foreign = server(ok)
        // 127.0.0.1 redirects to "localhost": the same machine, but another host for the client.
        val origin = server(redirectTo("http://localhost:${foreign.port}/landing"))
        val request = Request.Builder().url("http://127.0.0.1:${origin.port}/library")
            .header("X-Plex-Token", "plex-secret").header("X-Emby-Token", "emby-secret")
            .header("X-Emby-Authorization", "MediaBrowser Token=\"emby-secret\"").header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }

        assertTrue(origin.requests.single().has("X-Plex-Token"))
        val forwarded = foreign.requests.single()
        CredentialRedirectGuard.CREDENTIAL_HEADERS.forEach { assertFalse(it, forwarded.has(it)) }
        assertTrue(forwarded.has("Accept"))
    }

    @Test
    fun `a redirect on the same origin keeps the tokens`() {
        lateinit var origin: Server
        var first = true
        origin = server { if (first) { first = false; redirectTo("http://127.0.0.1:${origin.port}/next")() } else ok() }
        val request = Request.Builder().url("http://127.0.0.1:${origin.port}/start").header("X-Emby-Token", "emby-secret").build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertTrue(origin.requests.all { it.has("X-Emby-Token") })
    }

    @Test
    fun `a login body is not repeated at another host`() {
        val foreign = server(ok)
        val origin = server(redirectTo("http://localhost:${foreign.port}/Users/AuthenticateByName", code = 307))
        val request = Request.Builder().url("http://127.0.0.1:${origin.port}/Users/AuthenticateByName")
            .post("""{"Username":"u","Pw":"p"}""".toRequestBody("application/json".toMediaType()))
            .build()
        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
        assertTrue(foreign.requests.isEmpty())
    }

    @Test
    fun `only an https upgrade on the same host keeps credentials`() {
        val origin = "http://nas.local:8096/".toHttpUrl()
        assertTrue(CredentialRedirectGuard.keeps(origin, "http://nas.local:8096/web".toHttpUrl()))
        assertTrue(CredentialRedirectGuard.keeps(origin, "https://nas.local/".toHttpUrl()))
        assertFalse(CredentialRedirectGuard.keeps(origin, "http://nas.local:8080/".toHttpUrl()))
        assertFalse(CredentialRedirectGuard.keeps(origin, "https://auth.example.com/".toHttpUrl()))
        assertFalse(CredentialRedirectGuard.keeps("https://nas.local/".toHttpUrl(), "http://nas.local/".toHttpUrl()))
    }
}
