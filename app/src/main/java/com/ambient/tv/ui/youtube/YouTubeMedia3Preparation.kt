package com.ambient.tv.ui.youtube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import com.ambient.tv.youtube.YouTubePlayback
import kotlinx.coroutines.CancellationException

/** A pending manifest must never expose the previous video's source to the new session. */
@Composable
internal fun rememberYouTubeMedia3Playback(playback: YouTubePlayback): State<YouTubePlayback?> =
    key(playback.videoId, playback.uri, playback.deferredDash) {
        val pendingDash = playback.deferredDash
        produceState<YouTubePlayback?>(playback.takeIf { pendingDash == null }, playback) {
            value = pendingDash?.let { deferred ->
                val mpd = try {
                    deferred.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (mpd != null) playback.copy(uri = mpd, mimeType = "application/dash+xml", deferredDash = null)
                else if (playback.uri == playback.adaptiveVideoUri) {
                    // The native fallback is a video-only stream, not a Media3 source.
                    playback.copy(uri = playback.progressiveUri.orEmpty(), mimeType = null, deferredDash = null)
                } else playback.copy(deferredDash = null)
            } ?: playback
        }
    }
