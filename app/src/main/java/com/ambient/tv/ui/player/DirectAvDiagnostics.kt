package com.ambient.tv.ui.player

import android.content.Context
import androidx.media3.common.Format
import com.ambient.tv.R

/** Human-readable source -> output facts shown in the common Plex-style info panel. */
object DirectAvDiagnostics {
    fun video(context: Context, format: Format?, backend: String): String {
        val hdr = HdrSupport.assess(context, format)
        val source = hdr.source?.label ?: "SDR"
        val result = if (hdr.source == null) {
            context.getString(R.string.diag_source, source)
        } else {
            val display = displayVerdict(context, hdr)
            context.getString(R.string.diag_source_display, source, display)
        }
        return "$backend · SurfaceView · $result"
    }

    fun video(
        context: Context,
        codec: String?,
        dynamicRange: String?,
        bitDepth: Int?,
        profile: String?,
        backend: String,
    ): String {
        val hdr = HdrSupport.assess(context, codec, dynamicRange)
        val source = hdr.source?.label ?: "SDR"
        val result = if (hdr.source == null) {
            context.getString(R.string.diag_source, source)
        } else {
            val display = displayVerdict(context, hdr)
            context.getString(R.string.diag_source_display, source, display)
        }
        val details = listOfNotNull(
            bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
            profile?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        return listOf("$backend · SurfaceView", result, details).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun audio(
        context: Context,
        enabled: Boolean,
        codec: String?,
        runtimeFormat: String? = null,
        planMode: AudioOutputMode? = null,
    ): String {
        val name = codec?.uppercase()?.replace('_', '-') ?: context.getString(R.string.diag_audio_generic)
        runtimeFormat?.takeIf { it.isNotBlank() }?.let { format ->
            // mpv reports the format actually handed to its audio API. This still does not
            // certify a receiver's Atmos/DTS:X decoding mode.
            val output = if (format.startsWith("spdif", ignoreCase = true)) {
                context.getString(R.string.diag_bitstream)
            } else {
                context.getString(R.string.diag_pcm)
            }
            val mode = planMode?.let { " · ${modeLabel(context, it)}" }.orEmpty()
            return "$name → $output ($format)$mode"
        }
        planMode?.let { return "$name · ${modeLabel(context, it)}" }
        if (!enabled) return context.getString(R.string.diag_pcm_codec, name)
        if (!AudioPassthroughPolicy.isBitstreamCodec(codec)) return context.getString(R.string.diag_pcm_codec, name)
        val decision = AudioPassthroughPolicy.assess(context, enabled, codec)
        return if (decision.supported) context.getString(R.string.diag_passthrough_unconfirmed, name)
        else context.getString(R.string.diag_format_unconfirmed, name)
    }

    private fun displayVerdict(context: Context, hdr: HdrOutputAssessment): String = when {
        !hdr.displaySupportsSource -> context.getString(R.string.diag_display_incompatible)
        hdr.output != null && hdr.output != hdr.source -> context.getString(R.string.diag_display_compatible_as, hdr.output.label)
        else -> context.getString(R.string.diag_display_compatible)
    }

    private fun modeLabel(context: Context, mode: AudioOutputMode): String = when (mode) {
        AudioOutputMode.PASSTHROUGH -> context.getString(R.string.diag_mode_passthrough)
        AudioOutputMode.DTS_CORE -> context.getString(R.string.diag_mode_dts_core)
        AudioOutputMode.LOCAL_AC3 -> context.getString(R.string.diag_mode_local_ac3)
        AudioOutputMode.PCM -> context.getString(R.string.diag_mode_pcm)
    }
}
