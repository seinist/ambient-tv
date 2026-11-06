@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.MpvPlaybackController

internal fun rendererFormatIsDolbyVision(format: Format?): Boolean = format != null &&
    RendererCapabilityPolicy.isDolbyVision(format.codecs, format.sampleMimeType, null)

/** Selected tracks identify Dolby Vision before the first decoded frame is available. */
private fun media3RendererDolbyVision(player: ExoPlayer): Boolean =
    rendererFormatIsDolbyVision(player.videoFormat) || player.currentTracks.groups.any { group ->
        group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
            group.isTrackSelected(index) && rendererFormatIsDolbyVision(group.getTrackFormat(index))
        }
    }

private fun rendererHandle(metadata: PlaybackMetadata?): PlaybackHandle? =
    BackgroundPlaybackSession.activeHandle().takeIf {
        sameVideoSettingsPlayback(metadata, BackgroundPlaybackSession.snapshot.value.metadata)
    }

/** Read again when a choice is made: the stream may have identified itself after the menu opened. */
internal fun currentPlayerRendererAvailability(
    metadata: PlaybackMetadata?,
    observedDolbyVision: Boolean = false,
    mpvAvailable: Boolean = MpvBackendAvailability.isAvailable,
): RendererAvailability {
    val currentMetadata = BackgroundPlaybackSession.snapshot.value.metadata
        ?.takeIf { sameVideoSettingsPlayback(metadata, it) }
    // The title can keep the same route while a full video transcode replaces its source.
    // A panel retained across that replacement must use the current stream's source facts.
    val sourceMetadata = currentMetadata ?: metadata
    val handle = rendererHandle(metadata)
    val runtimeDolbyVision = when (handle) {
        is Media3PlaybackHandle -> media3RendererDolbyVision(handle.player)
        is MpvPlaybackController -> handle.dolbyVisionDetected || handle.sourceHdrStandard == HdrStandard.DOLBY_VISION
        else -> false
    }
    return RendererAvailability(
        dolbyVision = sourceMetadata?.hasVideo != false &&
            (sourceMetadata?.dolbyVisionSource == true ||
                sourceMetadata?.handheldHdrStandard == HdrStandard.DOLBY_VISION ||
                observedDolbyVision || runtimeDolbyVision),
        mpvAvailable = mpvAvailable,
    )
}

/** Runtime-aware shared selector for library, Live TV, YouTube and Monogram player panels. */
@Composable
internal fun rememberPlayerRendererAvailability(metadata: PlaybackMetadata?): RendererAvailability {
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    val sourceMetadata = snapshot.metadata?.takeIf { sameVideoSettingsPlayback(metadata, it) } ?: metadata
    val player = (rendererHandle(sourceMetadata) as? Media3PlaybackHandle)?.player
    var media3DolbyVision by remember(player, metadata?.route, metadata?.mediaId) {
        mutableStateOf(player?.let(::media3RendererDolbyVision) == true)
    }
    DisposableEffect(player, metadata?.route, metadata?.mediaId) {
        val listener = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                inputFormat: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                media3DolbyVision = rendererFormatIsDolbyVision(inputFormat)
            }
        }
        val tracksListener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                media3DolbyVision = player?.let(::media3RendererDolbyVision) == true
            }
        }
        player?.addAnalyticsListener(listener)
        player?.addListener(tracksListener)
        onDispose {
            player?.removeAnalyticsListener(listener)
            player?.removeListener(tracksListener)
        }
    }
    return currentPlayerRendererAvailability(sourceMetadata, media3DolbyVision)
}

/** No preference writes or engine callbacks for an unavailable or stale panel choice. */
internal fun selectAvailablePlayerRenderer(
    context: Context,
    metadata: PlaybackMetadata?,
    key: String,
    onSelected: (String, Boolean) -> Unit,
): Boolean {
    val active = BackgroundPlaybackSession.snapshot.value
    if (active.replacingSource || (active.metadata != null && !sameVideoSettingsPlayback(metadata, active.metadata))) return false
    if (!currentPlayerRendererAvailability(metadata).canSelect(key)) return false
    TitleVideoSettingsStore.rememberRenderer(context, metadata, key)
    onSelected(key, false)
    return true
}
