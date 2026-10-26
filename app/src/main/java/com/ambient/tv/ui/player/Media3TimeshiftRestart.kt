@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.livetv.LiveTimeshift

/** Replace the fixed cache offset before preparing a restarted live renderer. */
internal fun ExoPlayer.reopenTimeshiftAtLiveEdge(): Boolean {
    val item = currentMediaItem ?: return false
    val url = item.localConfiguration?.uri?.toString() ?: return false
    val buffer = LiveTimeshift.current ?: return false
    val reopened = buffer.reopenedUrl(url) ?: return false
    val (liveUrl, anchor) = if (buffer.recordingId != null)
        buffer.urlAt((LiveTimeshift.anchor.value ?: reopened.second) + currentPosition.coerceAtLeast(0L))
    else reopened
    LiveTimeshift.setAnchor(anchor)
    com.ambient.tv.livetv.logTimeshiftLine("Timeshift restart reason=media3-effects keep_position=${buffer.recordingId != null} " +
        "behind_live_ms=${buffer.liveWallMs - anchor}")
    setMediaItem(item.buildUpon().setUri(liveUrl).build(), 0L)
    return true
}

/**
 * Audio/route restart: reopen the buffer where the viewer is (not at the live edge), so a
 * passthrough or HDMI change neither skips the delay nor lands on the thin live edge again.
 */
internal fun ExoPlayer.reopenTimeshiftKeepingPosition(reason: String): Boolean {
    val item = currentMediaItem ?: return false
    val url = item.localConfiguration?.uri?.toString() ?: return false
    val (reopened, _) = LiveTimeshift.playbackUrlKeepingPosition(url, currentPosition, reason) ?: return false
    setMediaItem(item.buildUpon().setUri(reopened).build(), 0L)
    return true
}
