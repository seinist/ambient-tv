package com.ambient.tv.diagnostics

import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Log levels as android.util.Log numbers them, without depending on Android. */
internal object DiagnosticLevel {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    fun letter(priority: Int): Char = when (priority) {
        VERBOSE -> 'V'
        DEBUG -> 'D'
        INFO -> 'I'
        WARN -> 'W'
        ERROR -> 'E'
        ASSERT -> 'F'
        else -> '?'
    }
}

private val SECRET_QUERY = Regex("""(?i)([?&;]\s*(?:X-Plex-Token|X-Emby-Token|X-MediaBrowser-Token|api_?key|access_token|refresh_token|token|password|passwd|pwd|auth|sig|signature|lsig)=)[^&\s"'<>]+""")
private val SECRET_ASSIGN = Regex("""(?i)\b(X-Plex-Token|X-Emby-Token|X-MediaBrowser-Token|api_?key|access_?token|refresh_?token|token|password|passwd|client_secret)("?\s*[:=]\s*"?)[^\s&",;}]+""")
/** googlevideo URLs carry the viewer's IP, expiry and signatures in query and (manifests) path. */
private val GOOGLEVIDEO = Regex("""(?i)(https?://[a-z0-9.-]*googlevideo\.com)/[^\s"'<>]*""")
private val AUTH_HEADER = Regex("""(?i)\b(Authorization|Cookie|Set-Cookie|X-Emby-Authorization)(\s*[:=]\s*).+?(?=$|\n|",|\s{2})""")
private val BEARER = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
private val URL_USERINFO = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^/\s@:]+:[^/\s@]+@""")

/**
 * Removes credentials from log text: token/key/password parameters, Authorization and cookie
 * headers, bearer/basic values and user:password@ in URLs. Server addresses and paths stay, as
 * they are what a failure needs to be understood.
 */
internal fun redactDiagnostic(text: String): String = text
    .replace(GOOGLEVIDEO, "$1/…")
    .replace(URL_USERINFO, "$1…@")
    .replace(AUTH_HEADER, "$1$2…")
    .replace(BEARER, "$1 …")
    .replace(SECRET_QUERY, "$1…")
    .replace(SECRET_ASSIGN, "$1$2…")

internal fun stackTraceText(error: Throwable, maxLines: Int = 60): String {
    val writer = StringWriter()
    error.printStackTrace(PrintWriter(writer))
    val lines = writer.toString().lineSequence().filter(String::isNotBlank).toList()
    return if (lines.size <= maxLines) lines.joinToString("\n")
    else (lines.take(maxLines) + "\t… ${lines.size - maxLines} more").joinToString("\n")
}

internal fun formatDiagnosticLine(timeMs: Long, priority: Int, tag: String?, message: String, error: Throwable?): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timeMs))
    val thread = Thread.currentThread().name.take(24)
    val body = buildString {
        append(message.trim())
        if (error != null) {
            if (isNotEmpty()) append('\n')
            append(stackTraceText(error))
        }
    }
    return redactDiagnostic("$time ${DiagnosticLevel.letter(priority)} ${tag ?: "app"} [$thread] $body") + "\n"
}

/**
 * Size-bounded log on disk: appends to diagnostic-0.log and, past [maxBytes], shifts files up
 * (0→1→…); the oldest beyond [maxFiles] is dropped. Not thread-safe; one writer thread owns it.
 */
internal class RotatingLogFiles(private val dir: File, private val maxBytes: Long, private val maxFiles: Int) {
    fun current(): File = dir.resolve("diagnostic-0.log")

    @Synchronized
    fun append(text: String) {
        dir.mkdirs()
        val file = current()
        if (file.length() + text.length > maxBytes && file.length() > 0) rotate()
        current().appendText(text)
    }

    /** Oldest first, so a reader sees the log in order. */
    @Synchronized
    fun files(): List<File> = (maxFiles - 1 downTo 0).map { dir.resolve("diagnostic-$it.log") }.filter(File::isFile)

    @Synchronized
    fun clear() = files().forEach(File::delete)

    private fun rotate() {
        dir.resolve("diagnostic-${maxFiles - 1}.log").delete()
        for (index in maxFiles - 2 downTo 0) {
            val from = dir.resolve("diagnostic-$index.log")
            if (from.isFile) from.renameTo(dir.resolve("diagnostic-${index + 1}.log"))
        }
    }
}

/** Writes the ZIP the user downloads: the app log, a device summary and the logcat excerpt. */
internal fun writeDiagnosticZip(out: OutputStream, logFiles: List<File>, deviceInfo: String, logcat: String?, crashReport: String? = null) {
    ZipOutputStream(out.buffered()).use { zip ->
        fun entry(name: String, write: () -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            write()
            zip.closeEntry()
        }
        entry("device.txt") { zip.write(deviceInfo.toByteArray()) }
        entry("app.log") {
            if (logFiles.isEmpty()) zip.write("(empty)\n".toByteArray())
            logFiles.forEach { file -> file.inputStream().use { it.copyTo(zip) } }
        }
        if (logcat != null) entry("logcat.txt") { zip.write(logcat.toByteArray()) }
        if (crashReport != null) entry("crash.txt") { zip.write(redactDiagnostic(crashReport).toByteArray()) }
    }
}

/** A media URL as the log shows it: no query string or credentials, bounded length. */
internal fun diagnosticUrl(url: String?): String =
    redactDiagnostic(url.orEmpty().substringBefore('?').substringBefore('#')).take(160)

/**
 * Collapses repeats: the same message (digits ignored, so durations and counters do not count as
 * different) within [windowMs] of the previous one is suppressed. [admit] returns null for a
 * suppressed message, otherwise how many repeats of the previous message were suppressed.
 */
internal class DiagnosticRepeatFilter(private val windowMs: Long = 10_000) {
    private var lastKey: String? = null
    private var lastAt = 0L
    private var suppressed = 0

    @Synchronized
    fun admit(message: String, now: Long): Int? {
        val key = message.take(300).replace(Regex("""\d+"""), "#")
        if (key == lastKey && now - lastAt in 0 until windowMs) {
            suppressed++
            lastAt = now
            return null
        }
        val count = suppressed
        lastKey = key
        lastAt = now
        suppressed = 0
        return count
    }
}
