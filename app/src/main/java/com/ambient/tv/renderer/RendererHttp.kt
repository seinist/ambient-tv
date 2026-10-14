package com.ambient.tv.renderer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

// Minimal HTTP/1.1 for the renderer's control server, kept free of Android types for unit tests.

private const val MAX_LINE_LENGTH = 8192
private const val MAX_HEADERS = 64
internal const val MAX_RENDERER_BODY = 64 * 1024
private const val REQUEST_BUDGET_MS = 10_000L

internal class RendererRequest(
    val method: String,
    val path: String,
    /** Header names lower-cased. */
    val headers: Map<String, String>,
    val body: String,
)

/**
 * One request: line, headers and a Content-Length body. Every part is bounded (line length,
 * header count, body size, total time) so a LAN client cannot hold a worker thread.
 */
internal fun readRendererRequest(
    input: InputStream,
    deadlineMs: Long = System.currentTimeMillis() + REQUEST_BUDGET_MS,
    clock: () -> Long = System::currentTimeMillis,
): RendererRequest {
    val requestLine = readLine(input, deadlineMs, clock)
    val parts = requestLine.split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw IOException("Bad request line")
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val line = readLine(input, deadlineMs, clock)
        if (line.isEmpty()) break
        if (headers.size >= MAX_HEADERS) throw IOException("Too many headers")
        val separator = line.indexOf(':')
        if (separator <= 0) throw IOException("Bad header")
        headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
    }
    if (headers["transfer-encoding"] != null) throw IOException("Chunked bodies are not supported")
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    if (length !in 0..MAX_RENDERER_BODY) throw IOException("Body too large")
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        if (clock() > deadlineMs) throw IOException("Request too slow")
        val count = input.read(body, read, length - read)
        if (count < 0) throw IOException("Body truncated")
        read += count
    }
    return RendererRequest(parts[0].uppercase(Locale.ROOT), parts[1].substringBefore('?'), headers, String(body, Charsets.UTF_8))
}

private fun readLine(input: InputStream, deadlineMs: Long, clock: () -> Long): String {
    val result = StringBuilder()
    while (result.length < MAX_LINE_LENGTH) {
        if (clock() > deadlineMs) throw IOException("Request too slow")
        val ch = input.read()
        if (ch < 0) throw IOException("Connection closed")
        if (ch == '\n'.code) return result.toString().trimEnd('\r')
        result.append(ch.toChar())
    }
    throw IOException("Line too long")
}

internal fun writeRendererResponse(
    output: OutputStream,
    status: Int,
    reason: String,
    body: String = "",
    contentType: String? = if (body.isEmpty()) null else "text/xml; charset=\"utf-8\"",
    headers: List<Pair<String, String>> = emptyList(),
    server: String,
) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        append("SERVER: ").append(server).append("\r\n")
        contentType?.let { append("CONTENT-TYPE: ").append(it).append("\r\n") }
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("CONTENT-LENGTH: ").append(bytes.size).append("\r\n")
        append("CONNECTION: close\r\n\r\n")
    }
    output.write(head.toByteArray(Charsets.US_ASCII))
    output.write(bytes)
    output.flush()
}
