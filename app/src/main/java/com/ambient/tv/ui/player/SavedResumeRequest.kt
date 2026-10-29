package com.ambient.tv.ui.player

/** The rewind belongs to one user launch, never to its renderer or connection retries. */
internal class SavedResumeRequest {
    private var pending = true

    /** YouTube may receive its progress later; capture the allowance with that playback request. */
    fun takeSeconds(rewindSeconds: Int, eligible: Boolean = true): Int {
        val apply = pending && eligible
        pending = false
        return if (apply) rewindSeconds.coerceAtLeast(0) else 0
    }

    fun position(positionMs: Long, rewindSeconds: Int, eligible: Boolean = true): Long =
        resumeRewindPositionMs(positionMs, takeSeconds(rewindSeconds, eligible))

    /** Existing playback, Next Up and explicit retry already have their own position. */
    fun skip() { pending = false }
}
