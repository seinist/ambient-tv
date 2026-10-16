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
    setMediaItem(item.buildUpon().setUri(liveUrl).build(), 0L)
    return true
}
