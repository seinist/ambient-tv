package com.ambient.tv.diagnostics

import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One recorded crash: an uncaught exception or a system exit record (native crash, ANR). */
data class CrashSummary(val timeMs: Long, val kind: String, val headline: String)

/** A process exit as ApplicationExitInfo reports it, without depending on Android. */
internal data class ExitRecord(
    val timestampMs: Long,
    /** "crash", "crash_native" or "anr". */
    val kind: String,
    val description: String?,
    val processName: String?,
    val importance: Int,
    val pssKb: Long,
    /** Native tombstone (protobuf, API 31+) or ANR traces (text); null when unavailable. */
    val trace: (() -> InputStream?)? = null,
)

/**
 * Crash files in app storage (filesDir/crash), always on and independent of the optional
 * diagnostic log:
 * - last_crash.txt: the last uncaught Java/Kotlin exception, written synchronously by the
 *   crash handler before the process dies.
 * - exit-<time>-<kind>.txt: system exit records (native crash with tombstone, ANR with
 *   traces), read on the next start. A stored "last processed" time prevents duplicates.
 * Every file is size-bounded; sharing and the ZIP pass through [redactDiagnostic].
 */
internal class CrashReportStore(private val dir: File) {
    val lastCrashFile: File get() = dir.resolve(LAST_CRASH)
    private val stateFile: File get() = dir.resolve(STATE)

    /** Synchronous and exception-free: runs on a dying thread. */
    fun writeUncaught(timeMs: Long, threadName: String, error: Throwable, header: String, playback: String, breadcrumbs: List<String>) {
        runCatching {
            dir.mkdirs()
            val text = buildString {
                appendLine("Ambient TV crash report")
                appendLine("Kind: crash")
                appendLine("Time: ${formatTime(timeMs)} ($timeMs)")
                appendLine("Thread: $threadName")
                appendLine("Exception: ${error.javaClass.name}: ${error.message.orEmpty().take(300)}")
                appendLine()
                append(header.trimEnd()).appendLine()
                appendLine()
                appendLine("Playback: $playback")
                appendLine()
                appendLine("Stack trace:")
                appendLine(stackTraceText(error, maxLines = 120))
                appendLine()
                appendLine("Recent events:")
                if (breadcrumbs.isEmpty()) appendLine("(none)")
                breadcrumbs.forEach(::appendLine)
            }
            val bounded = redactDiagnostic(text).take(MAX_FILE_CHARS)
            val temp = dir.resolve("$LAST_CRASH.tmp")
            temp.writeText(bounded)
            if (!temp.renameTo(lastCrashFile)) { lastCrashFile.writeText(bounded); temp.delete() }
        }
    }

    /** Last processed exit timestamp; records at or before it were stored already. */
    fun lastProcessedExitMs(): Long = runCatching { stateFile.readText().trim().toLong() }.getOrDefault(0L)

    /**
     * Stores new exit records (newest [MAX_EXIT_RECORDS] at most) and advances the dedupe mark.
     * A Java crash already covered by last_crash.txt (within [SAME_CRASH_WINDOW_MS]) is skipped.
     * Returns the number of records written.
     */
    fun recordExits(records: List<ExitRecord>, header: String): Int {
        val last = lastProcessedExitMs()
        val fresh = records.filter { it.timestampMs > last }.sortedByDescending { it.timestampMs }
        if (fresh.isEmpty()) return 0
        dir.mkdirs()
        val javaCrashMs = uncaughtTimeMs()
        var written = 0
        for (record in fresh.take(MAX_EXIT_RECORDS)) {
            if (record.kind == "crash" && javaCrashMs != null && kotlin.math.abs(record.timestampMs - javaCrashMs) <= SAME_CRASH_WINDOW_MS) continue
            runCatching {
                val text = buildString {
                    appendLine("Ambient TV crash report")
                    appendLine("Kind: ${record.kind}")
                    appendLine("Time: ${formatTime(record.timestampMs)} (${record.timestampMs})")
                    appendLine("Process: ${record.processName.orEmpty()} importance=${record.importance} pss=${record.pssKb} KB")
                    appendLine("Description: ${record.description.orEmpty().take(500)}")
                    appendLine()
                    append(header.trimEnd()).appendLine()
                    val trace = record.trace?.let { open -> runCatching { open()?.use { readTrace(it, record.kind) } }.getOrNull() }
                    appendLine()
                    appendLine(if (record.kind == "crash_native") "Tombstone (text excerpt):" else "Trace:")
                    appendLine(trace ?: "(unavailable)")
                }
                dir.resolve("exit-${record.timestampMs}-${record.kind}.txt").writeText(redactDiagnostic(text).take(MAX_FILE_CHARS))
                written++
            }
        }
        // Only the newest records stay on disk.
        exitFiles().drop(MAX_EXIT_RECORDS).forEach(File::delete)
        runCatching { stateFile.writeText(fresh.first().timestampMs.toString()) }
        return written
    }

    /** Newest first. */
    fun exitFiles(): List<File> = (dir.listFiles { file -> file.name.startsWith("exit-") && file.name.endsWith(".txt") }?.toList() ?: emptyList())
        .sortedByDescending { it.name.removePrefix("exit-").substringBefore('-').toLongOrNull() ?: 0L }

    fun latest(): CrashSummary? {
        val uncaught = uncaughtTimeMs()?.let { time ->
            val exception = runCatching { lastCrashFile.useLines { lines -> lines.firstOrNull { it.startsWith("Exception: ") } } }.getOrNull()
            CrashSummary(time, "crash", exception?.removePrefix("Exception: ")?.take(160).orEmpty())
        }
        val exit = exitFiles().firstOrNull()?.let { file ->
            val time = file.name.removePrefix("exit-").substringBefore('-').toLongOrNull() ?: return@let null
            val kind = file.name.substringAfterLast('-').removeSuffix(".txt")
            val description = runCatching { file.useLines { lines -> lines.firstOrNull { it.startsWith("Description: ") } } }.getOrNull()
            CrashSummary(time, kind, description?.removePrefix("Description: ")?.take(160).orEmpty())
        }
        return listOfNotNull(uncaught, exit).maxByOrNull { it.timeMs }
    }

    /** Everything recorded, newest first, redacted and bounded; null without any crash. */
    fun report(): String? {
        val files = listOfNotNull(lastCrashFile.takeIf(File::isFile)) + exitFiles()
        if (files.isEmpty()) return null
        val ordered = files.sortedByDescending { fileTimeMs(it) }
        return redactDiagnostic(ordered.joinToString("\n\n========\n\n") { runCatching { it.readText() }.getOrDefault("") })
            .take(MAX_REPORT_CHARS)
    }

    fun clear() {
        lastCrashFile.delete()
        exitFiles().forEach(File::delete)
    }

    private fun uncaughtTimeMs(): Long? = if (!lastCrashFile.isFile) null else runCatching {
        lastCrashFile.useLines { lines -> lines.firstOrNull { it.startsWith("Time: ") } }
            ?.substringAfterLast('(')?.removeSuffix(")")?.toLong()
    }.getOrNull() ?: lastCrashFile.lastModified()

    private fun fileTimeMs(file: File): Long = if (file.name == LAST_CRASH) uncaughtTimeMs() ?: 0L
        else file.name.removePrefix("exit-").substringBefore('-').toLongOrNull() ?: 0L

    companion object {
        const val LAST_CRASH = "last_crash.txt"
        private const val STATE = "exit_state.txt"
        const val MAX_EXIT_RECORDS = 3
        const val MAX_FILE_CHARS = 256 * 1024
        const val MAX_REPORT_CHARS = 512 * 1024
        private const val MAX_TRACE_BYTES = 512 * 1024
        const val SAME_CRASH_WINDOW_MS = 10_000L

        fun formatTime(timeMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timeMs))

        /** ANR traces are text; a native tombstone is protobuf, so keep its readable strings. */
        internal fun readTrace(input: InputStream, kind: String): String {
            val bytes = readBounded(input, MAX_TRACE_BYTES)
            return if (kind == "crash_native") printableStrings(bytes) else String(bytes, Charsets.UTF_8)
        }

        private fun readBounded(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        /** Like `strings`: runs of at least [minLength] printable ASCII characters, one per line. */
        internal fun printableStrings(bytes: ByteArray, minLength: Int = 4): String {
            val result = StringBuilder()
            val run = StringBuilder()
            fun flush() {
                if (run.length >= minLength) result.append(run).append('\n')
                run.setLength(0)
            }
            for (byte in bytes) {
                val c = byte.toInt() and 0xff
                if (c in 0x20..0x7e || c == '\t'.code) run.append(c.toChar()) else flush()
            }
            flush()
            return result.toString()
        }
    }
}
