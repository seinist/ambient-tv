package com.ambient.tv.ui.player

/**
 * A Spotify start from Android Auto completes asynchronously. Only its still-current
 * selection may restore the previous media-session owner when the start fails.
 */
internal class CarSpotifySelection {
    internal data class Request(val generation: Long, val previousActive: Boolean)

    private var generation = 0L
    private var confirmed = false
    var active: Boolean = false
        private set

    fun begin(): Request {
        // An earlier pending start is not a playable Spotify session to restore.
        val previous = confirmed
        active = true
        return Request(++generation, previous)
    }

    /** Another source, user Stop or a newer playback generation supersedes pending starts. */
    fun clear() {
        active = false
        confirmed = false
        generation++
    }

    fun confirm(request: Request) {
        if (active && request.generation == generation) confirmed = true
    }

    /** Return true only when this failure belongs to the current selection. */
    fun rollbackFailed(request: Request): Boolean {
        if (!active || request.generation != generation) return false
        active = request.previousActive
        confirmed = request.previousActive
        generation++
        return true
    }
}
