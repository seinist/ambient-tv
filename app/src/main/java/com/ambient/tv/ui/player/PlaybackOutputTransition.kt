package com.ambient.tv.ui.player

import android.content.Context
import android.os.SystemClock

/** Only an app-requested TV output change may suppress a transient HDMI unplug notification. */
internal class PlaybackOutputChangeWindow(private val durationMs: Long = 10_000L) {
    private var playbackKey: Pair<String, String>? = null
    private var untilMs = 0L

    @Synchronized
    fun begin(key: Pair<String, String>?, nowMs: Long, television: Boolean, personalOutput: Boolean, extraMs: Long = 0L) {
        if (key == null || !television || personalOutput) {
            clear()
            return
        }
        val until = nowMs + durationMs + extraMs.coerceAtLeast(0L)
        untilMs = if (key == playbackKey) maxOf(untilMs, until) else until
        playbackKey = key
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

    /** [extraMs] covers a configured display-switch pause on top of the HDMI resync. */
    fun begin(context: Context, extraMs: Long = 0L) {
        if (!com.ambient.tv.ui.mobile.isTelevision(context)) return
        window.begin(key(), SystemClock.elapsedRealtime(), television = true,
            personalOutput = AudioPassthroughPolicy.probeCached(context).profile == "headphones", extraMs = extraMs)
    }

    fun active(): Boolean = window.active(key(), SystemClock.elapsedRealtime())

    fun suppressNoisy(context: Context): Boolean = active() &&
        com.ambient.tv.ui.mobile.isTelevision(context) &&
        AudioPassthroughPolicy.probeCached(context).profile != "headphones"

    fun clear() = window.clear()
}
