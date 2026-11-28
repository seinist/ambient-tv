package com.ambient.tv.ui.player.backend

/**
 * A private analysis player owns the playback hold until it has stopped.
 * The user's play/pause intent is independent of this temporary hold.
 * Confined to the owning player's control dispatcher (MPV worker / VLC main).
 */
internal class HdrScenePlaybackHold {
    var active: Boolean = false
        private set

    fun acquire() {
        check(!active) { "HDR analysis hold already acquired" }
        active = true
    }

    /** Idempotent so both adoption and the cancellation/failure path can release. */
    fun release() {
        active = false
    }

    fun allowsPlayback(requested: Boolean): Boolean = requested && !active
}
