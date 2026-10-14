package com.ambient.tv.ui.player

import com.ambient.tv.BuildConfig
import com.ambient.tv.diagnostics.DiagnosticLevel
import com.ambient.tv.diagnostics.DiagnosticLog
import timber.log.Timber

/** Timings only. Callers supply fixed stage names and numeric facts, never signed URLs. */
internal fun logPlaybackStartup(renderer: String, stage: String, startedNs: Long, facts: String = "") {
    if (startedNs == 0L || (!BuildConfig.DEBUG && !DiagnosticLog.enabled)) return
    val message = "renderer=$renderer stage=$stage open=$startedNs elapsed_ms=${(System.nanoTime() - startedNs) / 1_000_000} $facts"
    Timber.tag("playback-startup").d(message)
    // Numeric normalization would merge successive client attempts and lose their timings.
    DiagnosticLog.log(DiagnosticLevel.INFO, "playback-startup", message, deduplicate = false)
}
