@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the running engine actually decodes: sources without server metadata (Telegram). */
internal data class SessionFormat(
    val renderer: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val dimensions: String? = null,
    val hdrLabel: String? = null,
    val fps: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSession: AudioSession? = null,
) {
    val hasVideo: Boolean get() = width != null || dimensions != null || videoCodec != null
}

private fun Media3PlaybackHandle.format(): SessionFormat {
    val video = player.videoFormat
    val audio = player.audioFormat
    return SessionFormat(
        renderer = "Media3",
        width = video?.width?.takeIf { it > 0 },
        height = video?.height?.takeIf { it > 0 },
        hdrLabel = HdrSupport.sourceStandard(video)?.label,
        fps = video?.frameRate?.takeIf { it > 0f }?.toDouble(),
        videoCodec = video?.let { it.codecs ?: it.sampleMimeType },
        audioCodec = audio?.let { it.codecs ?: it.sampleMimeType },
        audioChannels = audio?.channelCount?.takeIf { it > 0 },
        audioSession = Media3AudioProcessing.session(player),
    )
}

/** Read off the main thread for MPV (its accessors may block briefly). */
private fun MpvPlaybackController.format() = SessionFormat(
    renderer = "MPV",
    dimensions = videoSize(),
    fps = containerFps(),
    videoCodec = videoCodec(),
    audioCodec = audioCodec(),
    audioSession = audioSession,
)

/** LibVLC's current tracks; counters are skipped, only the format is needed. */
private fun VlcPlaybackController.format(): SessionFormat {
    val stats = runtimeStats(includeCounters = false)
    return SessionFormat(
        renderer = "VLC",
        width = stats?.videoWidth?.takeIf { it > 0 },
        height = stats?.videoHeight?.takeIf { it > 0 },
        fps = stats?.videoFps?.takeIf { it > 0.0 },
        videoCodec = stats?.videoCodec,
        audioCodec = stats?.audioCodec,
        audioChannels = stats?.audioChannels?.takeIf { it > 0 },
        audioSession = audioSession,
    )
}

/** Polls the active engine while shown; empty until it knows its tracks. */
@Composable
internal fun rememberSessionFormat(sourceIdentity: String): SessionFormat {
    val format by produceState(SessionFormat(), sourceIdentity) {
        while (true) {
            value = when (val handle = BackgroundPlaybackSession.activeHandle()) {
                is Media3PlaybackHandle -> handle.format()
                is MpvPlaybackController -> withContext(Dispatchers.IO) { handle.format() }
                is VlcPlaybackController -> withContext(Dispatchers.IO) { handle.format() }
                else -> SessionFormat()
            }
            delay(1_000)
        }
    }
    return format
}

/** Renderer, resolution, HDR, frame rate and codecs, like the Plex/Jellyfin player. */
@Composable
internal fun SessionFormatBadges(format: SessionFormat) {
    format.renderer?.let { MetaBadge(it) }
    PlayerVideoBadges(
        dimensions = format.dimensions,
        width = format.width,
        height = format.height,
        hdrLabel = format.hdrLabel,
        hasVideo = format.hasVideo,
    )
    formatNerdFps(format.fps)?.let { MetaBadge(it) }
    format.videoCodec?.takeIf(String::isNotBlank)?.let { MetaBadge(playerCodecLabel(it)) }
    format.audioCodec?.takeIf(String::isNotBlank)?.let { codec ->
        MetaBadge(listOfNotNull(playerCodecLabel(codec), format.audioChannels?.let(::audioChannelLabel))
            .joinToString(" "))
    }
    audioOutputBadge(format.audioSession)?.let { MetaBadge(it) }
}
