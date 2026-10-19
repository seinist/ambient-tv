package com.ambient.tv.spotify

/**
 * A track change near the end of the previous track is the natural end of that title ("end of
 * title" timer); a jump earlier (next button, remote skip) is a new title chosen by the user.
 */
internal fun spotifyTrackEndedNaturally(lastPositionMs: Long, lastDurationMs: Long): Boolean =
    lastDurationMs > 0L && lastPositionMs >= lastDurationMs - SPOTIFY_TRACK_END_SLACK_MS

internal const val SPOTIFY_TRACK_END_SLACK_MS = 3_000L
/** Spotify on TV keeps its receiver; a pause this long ends the sleep session. */
internal const val SPOTIFY_SLEEP_IDLE_END_MS = 10 * 60_000L

/** Tracks Spotify playback for [com.ambient.tv.ui.player.SleepSession]. */
internal class SpotifyTrackWatch(private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private var lastTrackKey: String? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastPlaying = false
    private var lastAtMs = 0L
    /** Reported once to the sleep timer as "title ended". */
    var endedPending = false
        private set

    /** Returns true when the user picked another title (not a natural end). */
    fun update(state: SpotifyPlaybackState): Boolean {
        val now = clock()
        // The native bridge may not report every second: extrapolate the old track's position.
        val estimated = lastPositionMs + if (lastPlaying) (now - lastAtMs).coerceAtLeast(0L) else 0L
        val key = state.current?.let { track -> track.id.ifBlank { track.name } }
        var userChange = false
        if (key != null && lastTrackKey != null && key != lastTrackKey) {
            if (spotifyTrackEndedNaturally(estimated, lastDurationMs)) endedPending = true else userChange = true
        }
        if (key != null) lastTrackKey = key
        lastPositionMs = state.positionMs
        lastDurationMs = state.durationMs.takeIf { it > 0L } ?: state.current?.durationMs ?: 0L
        lastPlaying = state.playing
        lastAtMs = now
        return userChange
    }

    fun status(state: SpotifyPlaybackState): com.ambient.tv.ui.player.PlaybackStatus =
        com.ambient.tv.ui.player.PlaybackStatus(positionMs = state.positionMs, durationMs = state.durationMs, playing = state.playing, ended = endedPending)
            .also { endedPending = false }
}
