package com.ambient.tv.spotify

import java.io.IOException
import java.io.InputStream

// Minimal HTTP request handling for SpotifyQrLoginServer, kept free of Android types for unit tests.

private const val MAX_LINE_LENGTH = 8192
private const val MAX_HEADER_LINES = 64
private const val REQUEST_HEAD_BUDGET_MS = 10_000L

/**
 * Reads the request line and skips the headers (not needed). Line length, header count and the
 * total time are capped so one LAN client cannot hold the single serving thread indefinitely
 * (the socket timeout alone only bounds each read, not a byte-by-byte trickle).
 */
internal fun readRequestHead(
    input: InputStream,
    deadlineMs: Long = System.currentTimeMillis() + REQUEST_HEAD_BUDGET_MS,
    clock: () -> Long = System::currentTimeMillis,
): String {
    val requestLine = readLine(input, deadlineMs, clock)
    var headers = 0
    while (readLine(input, deadlineMs, clock).isNotEmpty()) {
        if (++headers > MAX_HEADER_LINES) throw IOException("Too many headers")
    }
    return requestLine
}

private fun readLine(input: InputStream, deadlineMs: Long, clock: () -> Long): String {
    val result = StringBuilder()
    while (result.length < MAX_LINE_LENGTH) {
        if (clock() > deadlineMs) throw IOException("Request too slow")
        val ch = input.read()
        if (ch < 0 || ch == '\n'.code) return result.toString().trimEnd('\r')
        result.append(ch.toChar())
    }
    throw IOException("Header too large")
}

internal fun escapeHtml(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal enum class QrLoginRoute { Login, Callback, NotFound, MethodNotAllowed }

/** Routes `GET <path>`; the token must match the path exactly (no decoding, no prefix match). */
internal fun qrLoginRoute(requestLine: String, loginToken: String): QrLoginRoute {
    val parts = requestLine.split(' ')
    if (parts.size < 2 || parts[0] != "GET") return QrLoginRoute.MethodNotAllowed
    return when (parts[1].substringBefore('#').substringBefore('?')) {
        "/login/$loginToken" -> QrLoginRoute.Login
        "/callback" -> QrLoginRoute.Callback
        else -> QrLoginRoute.NotFound
    }
}
