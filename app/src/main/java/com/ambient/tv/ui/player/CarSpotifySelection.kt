package com.ambient.tv.ui.player

/**
 * A Spotify start from Android Auto completes asynchronously. Only its still-current
 * selection may restore the previous media-session owner when the start fails.
 */
internal class CarSpotifySelection {
    internal data class Request(val generation: Long, val previousActive: Boolean)

    private var generation = 0L
    var active: Boolean = false
        private set

    fun begin(): Request {
        val previous = active
        active = true
        return Request(++generation, previous)
    }

    /** Another source, user Stop or a newer playback generation supersedes pending starts. */
    fun clear() {
        active = false
        generation++
    }

    /** Return true only when this failure belongs to the current selection. */
    fun rollbackFailed(request: Request): Boolean {
        if (!active || request.generation != generation) return false
        active = request.previousActive
        generation++
        return true
    }
}
