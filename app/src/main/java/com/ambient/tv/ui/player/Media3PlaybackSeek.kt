@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Player

/** Keep logical EOF out of DASH/HLS's final timestamp; return whether a decoder seek was sent. */
internal fun ExoPlayer.seekToPlaybackPosition(positionMs: Long): Boolean {
    val durationMs = duration
    if (isCurrentMediaItemLive || !isPlaybackEndSeek(positionMs, durationMs)) {
        seekTo(positionMs)
        return true
    }
    if (playbackState == Player.STATE_ENDED) return false
    val target = safePlaybackEndSeekTarget(currentPosition, durationMs) ?: return false
    val previousParameters = seekParameters
    // These messages reach Media3's playback handler in order. Only this end-tail seek is exact;
    // ordinary interactive seeks retain the renderer's fast keyframe policy.
    setSeekParameters(SeekParameters.EXACT)
    try {
        seekTo(target)
    } finally {
        setSeekParameters(previousParameters)
    }
    return true
}
