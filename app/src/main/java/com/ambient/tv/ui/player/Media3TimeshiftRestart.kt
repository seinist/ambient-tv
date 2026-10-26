@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.livetv.LiveTimeshift

/**
 * Audio/route or effect-chain restart: reopen the buffer where the viewer is (not at the live
 * edge), so a passthrough, HDMI or filter change neither skips the delay nor lands on the thin
 * live edge again.
 */
internal fun ExoPlayer.reopenTimeshiftKeepingPosition(reason: String): Boolean {
    val item = currentMediaItem ?: return false
    val url = item.localConfiguration?.uri?.toString() ?: return false
    val (reopened, _) = LiveTimeshift.playbackUrlKeepingPosition(url, currentPosition, reason) ?: return false
    setMediaItem(item.buildUpon().setUri(reopened).build(), 0L)
    return true
}
