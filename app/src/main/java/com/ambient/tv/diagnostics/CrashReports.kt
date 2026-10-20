package com.ambient.tv.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Always-on local crash capture (Settings → Diagnose → "Letzter Absturz"). Independent of the
 * optional diagnostic log; nothing leaves the device unless the user shares it.
 */
object CrashReports {
    @Volatile private var store: CrashReportStore? = null
    @Volatile private var appContext: Context? = null
    /** One line describing the current playback (engine, source, live); set by the app. */
    @Volatile var playbackDescription: () -> String = { "unknown" }

    internal fun init(context: Context, header: () -> String) {
        val app = context.applicationContext
        appContext = app
        store = CrashReportStore(app.filesDir.resolve("crash"))
        // Exit records are a binder call plus trace IO: never on the main thread.
        Thread({ runCatching { collectExitReasons(app, header()) } }, "CrashReports").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** From the default uncaught-exception handler, before chaining to the previous one. */
    internal fun recordUncaught(thread: Thread, error: Throwable, header: String) {
        val store = store ?: return
        val playback = runCatching { playbackDescription() }.getOrDefault("unknown")
        store.writeUncaught(System.currentTimeMillis(), thread.name, error, header, playback, CrashBreadcrumbs.snapshot())
    }

    private fun collectExitReasons(context: Context, header: String) {
        if (Build.VERSION.SDK_INT < 30) return
        val store = store ?: return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10).mapNotNull { info ->
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
                ApplicationExitInfo.REASON_ANR -> "anr"
                else -> return@mapNotNull null
            }
            ExitRecord(
                timestampMs = info.timestamp, kind = kind, description = info.description,
                processName = info.processName, importance = info.importance, pssKb = info.pss,
                // Tombstones are offered from API 31; ANR traces from API 30.
                trace = if (kind == "anr" || (kind == "crash_native" && Build.VERSION.SDK_INT >= 31)) ({ info.traceInputStream }) else null,
            )
        }
        val written = store.recordExits(records, header)
        if (written > 0) DiagnosticLog.log(DiagnosticLevel.WARN, "crash", "Recorded $written earlier process exit(s)", null)
    }

    fun latest(): CrashSummary? = store?.latest()

    /** Redacted report of all recorded crashes, for the diagnostic ZIP. */
    fun report(): String? = store?.report()

    fun clear() { store?.clear() }

    /**
     * Writes the redacted report to a shareable file and returns an ACTION_SEND chooser, or null
     * when nothing was recorded. Call off the main thread.
     */
    fun shareIntent(context: Context, title: CharSequence): Intent? {
        val text = report() ?: return null
        val dir = context.filesDir.resolve("crash").resolve("share")
        dir.mkdirs()
        dir.listFiles()?.forEach(File::delete)
        val file = dir.resolve("ambient-tv-crash.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Ambient TV crash report")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** No share target (common on TV): a copy in Android/data/<app>/files/Download. */
    fun saveCopy(context: Context): String? {
        val text = report() ?: return null
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir.resolve("exports")
        dir.mkdirs()
        val file = dir.resolve("ambient-tv-crash-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt")
        file.writeText(text)
        return file.absolutePath
    }
}
