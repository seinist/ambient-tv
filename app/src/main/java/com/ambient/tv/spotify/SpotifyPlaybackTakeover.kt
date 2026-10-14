package com.ambient.tv.spotify

/** A Spotify Connect session arrived while another app-owned playback session was active. */
data class SpotifyPlaybackTakeoverPrompt(
    val backgroundGeneration: Long,
    val title: String,
    val subtitle: String = "",
    val resumeCurrentPlayback: Boolean,
)

/**
 * App-wide foreground presence for takeover decisions.
 *
 * A reference count avoids the MainActivity -> OAuth callback Activity hand-off briefly marking the
 * whole app as background just because one Activity stops while the other starts.
 */
object SpotifyPlaybackTakeoverUiPresence {
    private val startedActivities = java.util.concurrent.atomic.AtomicInteger(0)

    val foreground: Boolean
        get() = startedActivities.get() > 0

    fun onActivityStarted() {
        startedActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        while (true) {
            val current = startedActivities.get()
            if (current <= 0) return
            if (startedActivities.compareAndSet(current, current - 1)) return
        }
    }

    internal fun resetForTest() { startedActivities.set(0) }
}

internal fun shouldPromptSpotifyTakeover(
    backgroundActive: Boolean,
    backgroundWantsPlayback: Boolean,
    uiForeground: Boolean,
    connectStarted: Boolean,
    spotifyPlaybackStarted: Boolean,
): Boolean = backgroundActive && backgroundWantsPlayback && uiForeground &&
    (connectStarted || spotifyPlaybackStarted)

internal fun shouldAutoTakeOverInBackground(
    backgroundActive: Boolean,
    backgroundWantsPlayback: Boolean,
    uiForeground: Boolean,
    spotifyPlaybackStarted: Boolean,
): Boolean = backgroundActive && backgroundWantsPlayback && !uiForeground && spotifyPlaybackStarted
