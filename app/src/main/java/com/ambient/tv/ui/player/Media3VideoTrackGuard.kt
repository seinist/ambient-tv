@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.os.Handler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

internal fun media3HasUnsupportedVideo(tracks: Tracks, videoDisabled: Boolean): Boolean {
    if (videoDisabled) return false
    val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    return media3HasUnsupportedVideo(video.map { group -> (0 until group.length).any(group::isTrackSupported) }, videoDisabled)
}

internal fun media3HasUnsupportedVideo(videoGroupsSupported: List<Boolean>, videoDisabled: Boolean): Boolean =
    !videoDisabled && videoGroupsSupported.isNotEmpty() && videoGroupsSupported.none { it }

/** Unsupported video can be omitted without onPlayerError while audio reaches READY. */
@Composable
internal fun Media3VideoTrackGuard(player: ExoPlayer, onUnsupported: () -> Unit) {
    val callback by rememberUpdatedState(onUnsupported)
    DisposableEffect(player) {
        val handler = Handler(player.applicationLooper)
        var reported = false
        val check = Runnable {
            if (!reported && player.playbackState == Player.STATE_READY && player.videoFormat == null &&
                (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player === player &&
                media3HasUnsupportedVideo(player.currentTracks, C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes)) {
                reported = true
                callback()
            }
        }
        fun schedule() { handler.removeCallbacks(check); handler.postDelayed(check, 800) }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TRACKS_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) schedule()
            }
        }
        player.addListener(listener)
        schedule()
        onDispose { handler.removeCallbacks(check); player.removeListener(listener) }
    }
}
