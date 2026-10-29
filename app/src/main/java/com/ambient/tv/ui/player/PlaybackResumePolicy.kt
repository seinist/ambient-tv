package com.ambient.tv.ui.player

/** Apply once to saved progress, before converting an absolute media time to an engine time. */
internal fun resumeRewindPositionMs(positionMs: Long, rewindSeconds: Int): Long =
    (positionMs.coerceAtLeast(0L) - rewindSeconds.coerceIn(0, 60) * 1_000L).coerceAtLeast(0L)

internal data class PlaybackResumeCapabilities(
    val pauseRewind: Boolean = false,
    val savedResumeRewind: Boolean = false,
)

internal fun playbackResumeCapabilities(
    hasVideo: Boolean,
    isLive: Boolean,
    seekable: Boolean,
    savesProgress: Boolean,
): PlaybackResumeCapabilities = PlaybackResumeCapabilities(
    pauseRewind = hasVideo && seekable,
    savedResumeRewind = hasVideo && seekable && !isLive && savesProgress,
)

/**
 * Only an explicit user pause arms a jump. Technical pauses never call [userPaused].
 * A user seek or a source/renderer replacement clears the pending jump, including a
 * seek preview that has not reached the decoder yet. Time is monotonic, not wall-clock time.
 */
internal class PauseRewindPolicy {
    private var pausedAtMs: Long? = null
    private var pausedPositionMs = 0L

    fun clear() { pausedAtMs = null }

    fun userPaused(nowMs: Long, positionMs: Long, wasPlaying: Boolean, eligible: Boolean) {
        if (!eligible) {
            clear()
        } else if (wasPlaying) {
            pausedAtMs = nowMs
            pausedPositionMs = positionMs.coerceAtLeast(0L)
        }
        // A repeated Pause command must not restart the minimum-pause countdown.
    }

    /** Consumes the pause even when disabled, too short, or no longer eligible. */
    fun resumeTarget(
        nowMs: Long,
        positionMs: Long,
        alreadyPlaying: Boolean,
        eligible: Boolean,
        rewindSeconds: Int,
        minimumPauseSeconds: Int,
        earliestMs: Long = 0L,
        latestMs: Long = Long.MAX_VALUE,
    ): Long? {
        val pausedAt = pausedAtMs
        clear()
        if (pausedAt == null || alreadyPlaying || !eligible || rewindSeconds <= 0 || nowMs < pausedAt) return null
        if (nowMs - pausedAt < minimumPauseSeconds.coerceIn(0, 60) * 1_000L) return null
        val position = positionMs.coerceAtLeast(0L)
        // Commands outside the UI may have moved the player too. Allow only pause settling.
        if (kotlin.math.abs(position - pausedPositionMs) > PAUSE_SETTLE_TOLERANCE_MS) return null
        val earliest = earliestMs.coerceAtLeast(0L)
        if (latestMs < earliest) return null
        return resumeRewindPositionMs(position, rewindSeconds).coerceIn(earliest, latestMs)
            .takeUnless { it == position }
    }

    private companion object {
        const val PAUSE_SETTLE_TOLERANCE_MS = 1_500L
    }
}
