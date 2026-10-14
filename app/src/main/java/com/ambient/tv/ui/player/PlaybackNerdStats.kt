@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.ambient.tv.data.local.Nnedi3QualityCatalog
import java.util.Locale

/**
 * Runtime-only diagnostics used by the optional "Stats for Nerds" panel.
 * Event listeners stay attached so decoder names are not missed when the panel is opened later;
 * counter/buffer polling only runs while [enabled] is true.
 */
data class Media3NerdStats(
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val bandwidthEstimateBitsPerSecond: Long? = null,
    val bufferDurationMs: Long? = null,
    val renderedVideoFrames: Int? = null,
    val droppedVideoFrames: Int? = null,
    val skippedVideoFrames: Int? = null,
    val maxConsecutiveDroppedFrames: Int? = null,
    val videoDecoderInitCount: Int? = null,
    val audioDecoderInitCount: Int? = null,
    val averageVideoProcessingOffsetUs: Long? = null,
    val surfaceSize: String? = null,
    val audioUnderruns: Int = 0,
)

@Composable
fun rememberMedia3NerdStats(player: ExoPlayer, enabled: Boolean): Media3NerdStats {
    var stats by remember(player) { mutableStateOf(Media3NerdStats()) }

    DisposableEffect(player) {
        val listener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                stats = stats.copy(videoDecoderName = decoderName.takeIf { it.isNotBlank() })
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                stats = stats.copy(audioDecoderName = decoderName.takeIf { it.isNotBlank() })
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                stats = stats.copy(
                    bandwidthEstimateBitsPerSecond = bitrateEstimate.takeIf { it > 0L },
                )
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                stats = stats.copy(audioUnderruns = stats.audioUnderruns + 1)
            }
        }
        player.addAnalyticsListener(listener)
        onDispose { player.removeAnalyticsListener(listener) }
    }

    LaunchedEffect(player, enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            val videoCounters = player.videoDecoderCounters
            val audioCounters = player.audioDecoderCounters
            videoCounters?.ensureUpdated()
            audioCounters?.ensureUpdated()
            val surface = player.surfaceSize
            stats = stats.copy(
                bufferDurationMs = player.totalBufferedDuration.takeIf { it >= 0L },
                renderedVideoFrames = videoCounters?.renderedOutputBufferCount,
                droppedVideoFrames = videoCounters?.droppedBufferCount,
                skippedVideoFrames = videoCounters?.skippedOutputBufferCount,
                maxConsecutiveDroppedFrames = videoCounters?.maxConsecutiveDroppedBufferCount,
                videoDecoderInitCount = videoCounters?.decoderInitCount,
                audioDecoderInitCount = audioCounters?.decoderInitCount,
                averageVideoProcessingOffsetUs = videoCounters?.let { counters ->
                    val count = counters.videoFrameProcessingOffsetCount
                    if (count > 0) counters.totalVideoFrameProcessingOffsetUs / count else null
                },
                surfaceSize = if (surface.width > 0 && surface.height > 0) "${surface.width}×${surface.height}" else null,
            )
            delay(750)
        }
    }
    return stats
}

internal fun formatNerdBitrate(bitsPerSecond: Long?): String? {
    val value = bitsPerSecond?.takeIf { it > 0L } ?: return null
    return when {
        value >= 1_000_000L -> String.format(Locale.US, "%.2f Mbit/s", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.0f kbit/s", value / 1_000.0)
        else -> "$value bit/s"
    }
}

internal fun formatNerdByteRate(bytesPerSecond: Long?): String? {
    val value = bytesPerSecond?.takeIf { it > 0L } ?: return null
    return when {
        value >= 1_048_576L -> String.format(Locale.US, "%.2f MiB/s", value / 1_048_576.0)
        value >= 1_024L -> String.format(Locale.US, "%.1f KiB/s", value / 1_024.0)
        else -> "$value B/s"
    }
}


internal fun formatNerdBytes(bytes: Long?): String? {
    val value = bytes?.takeIf { it >= 0L } ?: return null
    return when {
        value >= 1_073_741_824L -> String.format(Locale.US, "%.2f GiB", value / 1_073_741_824.0)
        value >= 1_048_576L -> String.format(Locale.US, "%.2f MiB", value / 1_048_576.0)
        value >= 1_024L -> String.format(Locale.US, "%.1f KiB", value / 1_024.0)
        else -> "$value B"
    }
}

internal fun formatNerdDurationMs(milliseconds: Long?): String? {
    val value = milliseconds?.takeIf { it >= 0L } ?: return null
    return if (value >= 1000L) String.format(Locale.US, "%.2f s", value / 1000.0) else "$value ms"
}

internal fun formatNerdSeconds(seconds: Double?): String? {
    val value = seconds?.takeIf { it.isFinite() } ?: return null
    return String.format(Locale.US, "%.3f s", value)
}

internal fun formatNerdFps(fps: Double?): String? {
    val value = fps?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return String.format(Locale.US, "%.3f fps", value)
}

/** "23.976 → 59.940 fps" once filters (SVP) change the rate; otherwise the single rate. */
internal fun formatNerdFpsChange(source: Double?, output: Double?): String? {
    val from = source?.takeIf { it.isFinite() && it > 0.0 }
    val to = output?.takeIf { it.isFinite() && it > 0.0 }
    if (from != null && to != null && kotlin.math.abs(to - from) > from * 0.01) {
        return String.format(Locale.US, "%.3f → %.3f fps", from, to)
    }
    return formatNerdFps(from ?: to)
}

internal fun formatNerdHz(hz: Double?): String? {
    val value = hz?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return String.format(Locale.US, "%.3f Hz", value)
}
internal fun formatMpvScalerName(key: String?): String? = when (key?.trim()?.lowercase(Locale.US)) {
    null, "" -> null
    "bilinear" -> "Bilinear"
    "bicubic" -> "Bicubic"
    "catmull_rom" -> "Catmull-Rom"
    "mitchell" -> "Mitchell"
    "spline16" -> "Spline16"
    "spline36" -> "Spline36"
    "spline64" -> "Spline64"
    "lanczos" -> "Lanczos"
    "ewa_lanczos" -> "EWA Lanczos"
    "ewa_lanczossharp" -> "EWA LanczosSharp"
    "ewa_lanczos4sharpest" -> "EWA Lanczos4Sharpest"
    "nearest" -> "Nearest"
    "oversample" -> "Oversample"
    else -> Nnedi3QualityCatalog.byKey[key]?.statsName
        ?: com.ambient.tv.data.local.VideoImageCatalog.mpvScalers[key]
        ?: key
}

internal fun formatMpvUpscalerForStats(
    configuredKey: String,
    runtimeScaler: String?,
    activeShaderPreset: String?,
    upscaling: Boolean? = null,
): String? {
    if (upscaling == false) return "off"
    val shader = formatMpvScalerName(activeShaderPreset)
    val runtime = formatMpvScalerName(runtimeScaler)
    if (shader != null) {
        return listOfNotNull("$shader (loaded; conditional)", runtime?.let { "$it finish" }).joinToString(" → ")
    }
    // Selected GLSL luma upscalers (NNEDI3, FSR, …) stay unloaded until their //!WHEN can
    // pass or while SVP owns the frame path. Do not collapse that choice to the builtin
    // finish scaler (usually Lanczos) or the overlay looks like Lanczos is selected.
    val configuredShader = configuredKey.takeIf { it in com.ambient.tv.data.local.VideoImageCatalog.mpvShaders }
        ?.let { formatMpvScalerName(it) }
    if (configuredShader != null) {
        return listOfNotNull("$configuredShader (configured; idle)", runtime?.let { "$it finish" }).joinToString(" → ")
    }
    return runtime ?: formatMpvScalerName(configuredKey)?.let { "$it (configured)" }
}
