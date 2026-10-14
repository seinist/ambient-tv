package com.ambient.tv.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Optional diagnostic log for release builds (Settings → Diagnostics). Off by default; while on,
 * warnings and errors from Timber, failed HTTP calls, player errors, error notices and crashes
 * are written to a size-bounded file in app storage, never to shared storage, with credentials
 * removed. The user exports it as a ZIP together with the app's own logcat lines.
 */
object DiagnosticLog {
    private const val MAX_FILE_BYTES = 512L * 1024
    private const val MAX_FILES = 6

    @Volatile
    var enabled: Boolean = false
        private set

    private var appContext: Context? = null
    private var files: RotatingLogFiles? = null
    // Bounded: a log storm (e.g. a server polled while offline) drops the oldest pending lines
    // instead of growing memory without limit.
    private val writer = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(2_000),
        { runnable -> Thread(runnable, "DiagnosticLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val repeats = DiagnosticRepeatFilter()

    fun init(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        appContext = app
        files = RotatingLogFiles(app.filesDir.resolve("diagnostics"), MAX_FILE_BYTES, MAX_FILES)
        Timber.plant(DiagnosticTree)
        installCrashHandler()
        setEnabled(enabled)
    }

    fun setEnabled(value: Boolean) {
        if (value == enabled) return
        if (value) {
            enabled = true
            log(DiagnosticLevel.INFO, "diagnostics", "Logging on · ${deviceSummary().lineSequence().take(4).joinToString(" · ")}", null)
        } else {
            log(DiagnosticLevel.INFO, "diagnostics", "Logging off", null)
            enabled = false
        }
    }

    fun log(priority: Int, tag: String?, message: String, error: Throwable? = null, deduplicate: Boolean = true) {
        if (!enabled) return
        val files = files ?: return
        val now = System.currentTimeMillis()
        // The same message again within seconds (numbers aside) is counted, not written again.
        val suppressed = if (deduplicate) repeats.admit("$priority|$tag|$message", now) ?: return else 0
        // Formatting reads the caller's thread name; the disk write happens off that thread.
        val line = runCatching {
            (if (suppressed > 0) formatDiagnosticLine(now, DiagnosticLevel.INFO, "diagnostics", "previous message repeated $suppressed more times", null) else "") +
                formatDiagnosticLine(now, priority, tag, message, error)
        }.getOrNull() ?: return
        runCatching { writer.execute { runCatching { files.append(line) } } }
    }

    fun warn(tag: String, message: String, error: Throwable? = null) = log(DiagnosticLevel.WARN, tag, message, error)
    fun error(tag: String, message: String, error: Throwable? = null) = log(DiagnosticLevel.ERROR, tag, message, error)

    /** An error notice as the user saw it (its text is already free of credentials). */
    fun notice(notice: com.ambient.tv.notifications.AppNotice) {
        if (!enabled) return
        val text = runCatching { appContext!!.getString(notice.message, *notice.args.toTypedArray()) }
            .getOrElse { "message #${notice.message} ${notice.args}" }
        log(DiagnosticLevel.WARN, "notice", "${notice.kind} ${notice.key} [${notice.systems.joinToString()}]: $text")
    }

    /** Waits for pending lines, then writes the ZIP. Call off the main thread. */
    fun export(out: OutputStream) {
        val files = files ?: error("Diagnostics not initialized")
        runCatching { writer.submit {}.get(5, TimeUnit.SECONDS) }
        writeDiagnosticZip(out, files.files(), deviceSummary(), readLogcat())
    }

    fun clear() {
        val files = files ?: return
        runCatching { writer.submit { files.clear() }.get(5, TimeUnit.SECONDS) }
    }

    /** Bytes currently on disk. */
    fun sizeBytes(): Long = files?.files()?.sumOf { it.length() } ?: 0L

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled) {
                // The process is about to die: write synchronously, the executor may never run.
                runCatching {
                    files?.append(formatDiagnosticLine(System.currentTimeMillis(), DiagnosticLevel.ASSERT, "crash",
                        "Uncaught exception on ${thread.name}", error))
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun deviceSummary(): String {
        val context = appContext
        val version = runCatching {
            val info = context!!.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
        }.getOrDefault("?")
        val television = context?.let { runCatching { com.ambient.tv.ui.mobile.isTelevision(it) }.getOrNull() }
        return buildString {
            appendLine("App: ${context?.packageName} $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Form factor: ${if (television == true) "TV" else "handheld"}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Exported: ${java.util.Date()}")
        }
    }

    /** The app's own logcat lines (an app reads only its own UID's), credentials removed. */
    private fun readLogcat(): String? = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "8000").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(5, TimeUnit.SECONDS)
        text.lineSequence().joinToString("\n") { redactDiagnostic(it) }
    }.onFailure { Log.w("DiagnosticLog", "logcat unavailable", it) }.getOrNull()
}

/** Warnings and errors (and info, while diagnostics are on) from Timber. */
private object DiagnosticTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = DiagnosticLog.enabled && priority >= DiagnosticLevel.INFO
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Timber appends the stack trace to message already; do not add it twice.
        DiagnosticLog.log(priority, tag, message, null)
    }
}
