package com.ambient.tv.livetv

/** Logcat tag for timeshift events: `adb logcat -s timeshift`. */
internal const val TIMESHIFT_LOG_TAG = "timeshift"

/** Test hook: receives every timeshift log line in addition to logcat. */
@Volatile internal var timeshiftLogSink: ((String) -> Unit)? = null

/**
 * One line per timeshift event that can cause a visible jump: upstream end/error, reconnect
 * (gap, byte position, dropped bytes), a reader that fell out of the ring, and renderer
 * restarts that reopen the buffer. Written to logcat and, while diagnostics are on, to the file.
 */
internal fun logTimeshiftLine(line: String) {
    timeshiftLogSink?.invoke(line)
    runCatching { android.util.Log.i(TIMESHIFT_LOG_TAG, line) }
    runCatching {
        com.ambient.tv.diagnostics.DiagnosticLog.log(com.ambient.tv.diagnostics.DiagnosticLevel.INFO, TIMESHIFT_LOG_TAG, line,
            deduplicate = false)
    }
}
