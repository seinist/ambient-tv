package com.ambient.tv.spotify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * LAN endpoint for the TV QR sign-in. The QR code only carries `http://<tv>:<port>/login/<token>`
 * (a full Spotify authorize URL is too long for a comfortably scannable code):
 *
 * - `GET /login/<token>` answers 302 to the Spotify authorize URL.
 * - `GET /callback?code=…&state=…` arrives from the HTTPS relay page that Spotify redirected the
 *   phone to; the handler exchanges the code and the phone gets a result page.
 *
 * Requests are served by a small pool, so one slow client cannot block the phone's callback;
 * the listener closes itself after [lifetimeMs].
 * Nothing secret passes through: the code is useless without the PKCE verifier kept on the TV.
 */
internal class SpotifyQrLoginServer private constructor(
    private val server: ServerSocket,
    private val loginToken: String,
    private val handler: Handler,
    lifetimeMs: Long,
) : Closeable {
    interface Handler {
        fun authorizeUrl(): String
        /** Blocking: exchanges the code and returns the page shown on the phone. */
        fun callback(query: Uri): Page
        fun expired(server: SpotifyQrLoginServer)
    }

    data class Page(val success: Boolean, val title: String, val message: String)

    val host: String = server.inetAddress.hostAddress.orEmpty()
    val port: Int = server.localPort
    val loginUrl: String = "http://$host:$port/login/$loginToken"

    private val deadline = System.currentTimeMillis() + lifetimeMs
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        0, MAX_WORKERS, 10, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        { runnable -> Thread(runnable, "spotify-qr-request").apply { isDaemon = true } },
    )

    init {
        server.soTimeout = ACCEPT_POLL_MS
        Thread({ acceptLoop() }, "spotify-qr-login").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            if (System.currentTimeMillis() > deadline) {
                close()
                handler.expired(this)
                return
            }
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            // Busy (all workers in use): drop this connection instead of queueing without bound.
            val handed = runCatching { workers.execute { runCatching { socket.use(::serve) } } }.isSuccess
            if (!handed) runCatching { socket.close() }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val requestLine = readRequestHead(socket.getInputStream())
        when (qrLoginRoute(requestLine, loginToken)) {
            QrLoginRoute.Login -> respond(socket, 302, "Found", location = handler.authorizeUrl())
            QrLoginRoute.Callback -> respond(socket, 200, "OK",
                page = handler.callback(Uri.parse("http://localhost" + requestLine.split(' ')[1])))
            QrLoginRoute.NotFound -> respond(socket, 404, "Not Found")
            QrLoginRoute.MethodNotAllowed -> respond(socket, 405, "Method Not Allowed")
        }
    }

    private fun respond(socket: Socket, status: Int, reason: String, location: String? = null, page: Page? = null) {
        val body = page?.let(::html).orEmpty().toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (location != null) append("Location: $location\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun html(page: Page): String {
        val color = if (page.success) "#1DB954" else "#E5534B"
        return """<!doctype html><html><head><meta charset="utf-8">
            |<meta name="viewport" content="width=device-width,initial-scale=1">
            |<title>${escapeHtml(page.title)}</title>
            |<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
            |background:#121212;color:#fff;font:16px/1.5 system-ui,sans-serif;text-align:center;padding:0 16px}
            |h1{color:$color;font-size:1.4em}</style></head>
            |<body><main><h1>${escapeHtml(page.title)}</h1><p>${escapeHtml(page.message)}</p></main></body></html>""".trimMargin()
    }

    override fun close() {
        runCatching { server.close() }
        workers.shutdown()
    }

    companion object {
        private const val ACCEPT_POLL_MS = 1_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_WORKERS = 4

        fun start(context: Context, loginToken: String, handler: Handler, lifetimeMs: Long): SpotifyQrLoginServer? {
            val address = lanAddress(context) ?: return null
            return runCatching {
                SpotifyQrLoginServer(ServerSocket(0, 4, address), loginToken, handler, lifetimeMs)
            }.getOrNull()
        }

        /**
         * IPv4 address on the Wi-Fi/Ethernet LAN the phone shares with the TV. VPN networks (the
         * app's own WireGuard route is the active network for this app while it runs) and mobile
         * data are skipped: a QR code pointing at a tunnel address is unreachable from the phone.
         */
        @Suppress("DEPRECATION")
        private fun lanAddress(context: Context): InetAddress? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val lan = runCatching {
                connectivity?.allNetworks.orEmpty().firstNotNullOfOrNull { network ->
                    val caps = connectivity?.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        return@firstNotNullOfOrNull null
                    }
                    connectivity.getLinkProperties(network)?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                }
            }.getOrNull()
            if (lan != null) return lan
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { nic -> nic.isUp && !nic.isLoopback && !nic.isPointToPoint &&
                        listOf("tun", "wg", "ppp", "rmnet", "ccmni").none { nic.name.startsWith(it) } }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            }.getOrNull()
        }
    }
}
