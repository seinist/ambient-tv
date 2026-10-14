package com.ambient.tv.ui.player

import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import java.util.WeakHashMap
import timber.log.Timber

internal fun needsDolbyVisionResumePriming(manufacturer: String, sdk: Int, hdr: HdrStandard?, positionMs: Long) =
    manufacturer.equals("NVIDIA", ignoreCase = true) && sdk <= 30 &&
        hdr == HdrStandard.DOLBY_VISION && positionMs > 0

/** NVIDIA's DV decoder needs its initial frame before a cold seek in some HEVC files. */
internal object Media3ResumePrimer {
    private val sessions = WeakHashMap<ExoPlayer, Primer>()
    fun start(player: ExoPlayer, positionMs: Long) {
        release(player)
        sessions[player] = Primer(player, positionMs).also { it.start() }
    }
    fun retarget(player: ExoPlayer, positionMs: Long): Boolean {
        val session = sessions[player]?.takeUnless { it.primed } ?: return false
        session.target = positionMs.coerceAtLeast(0)
        return true
    }
    fun release(player: ExoPlayer) { sessions.remove(player)?.finish(restoreVolume = false) }

    private class Primer(val player: ExoPlayer, var target: Long) : Player.Listener {
        var primed = false
        private var internalSeek = false
        private val volume = player.volume
        private val handler = Handler(player.applicationLooper)
        private val timeout = Runnable {
            Timber.w("Dolby Vision resume priming timed out; applying requested position")
            internalSeek = true
            player.seekTo(target)
            finish()
        }
        fun start() {
            player.volume = 0f
            player.addListener(this)
            handler.postDelayed(timeout, 8_000)
        }
        override fun onRenderedFirstFrame() {
            if (primed) return
            primed = true
            internalSeek = true
            player.seekTo(target)
            internalSeek = false
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A direct user seek supersedes the pending initialization target.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !internalSeek && !primed) finish()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (primed && playbackState == Player.STATE_READY && player.playbackState == Player.STATE_READY) {
                Timber.i("Dolby Vision resume primed at %d ms", player.currentPosition)
                finish()
            }
        }
        override fun onPlayerError(error: PlaybackException) = finish()
        fun finish(restoreVolume: Boolean = true) {
            handler.removeCallbacks(timeout)
            player.removeListener(this)
            if (restoreVolume && player.volume == 0f) player.volume = volume
            if (sessions[player] === this) sessions.remove(player)
        }
    }
}
