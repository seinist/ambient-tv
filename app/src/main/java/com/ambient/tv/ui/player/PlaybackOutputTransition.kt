package com.ambient.tv.ui.player

import android.content.Context
import android.os.SystemClock

/** Only an app-requested TV output change may suppress a transient HDMI unplug notification. */
internal class PlaybackOutputChangeWindow(private val durationMs: Long = 10_000L) {
    private var playbackKey: Pair<String, String>? = null
    private var untilMs = 0L

    @Synchronized
    fun begin(key: Pair<String, String>?, nowMs: Long, television: Boolean, personalOutput: Boolean) {
        if (key == null || !television || personalOutput) {
            clear()
            return
        }
        playbackKey = key
        untilMs = nowMs + durationMs
    }

    @Synchronized
    fun active(key: Pair<String, String>?, nowMs: Long): Boolean =
        key != null && key == playbackKey && nowMs < untilMs

    @Synchronized
    fun clear() { playbackKey = null; untilMs = 0L }
}

internal object PlaybackOutputTransition {
    private val window = PlaybackOutputChangeWindow()
    private fun key() = BackgroundPlaybackSession.snapshot.value.metadata?.let { it.source to it.mediaId }

    fun begin(context: Context) {
        if (!com.ambient.tv.ui.mobile.isTelevision(context)) return
        window.begin(key(), SystemClock.elapsedRealtime(), television = true,
            personalOutput = AudioPassthroughPolicy.probeCached(context).profile == "headphones")
    }

    fun active(): Boolean = window.active(key(), SystemClock.elapsedRealtime())

    fun suppressNoisy(context: Context): Boolean = active() &&
        com.ambient.tv.ui.mobile.isTelevision(context) &&
        AudioPassthroughPolicy.probeCached(context).profile != "headphones"

    fun clear() = window.clear()
}
