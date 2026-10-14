package com.ambient.tv.ui.player

import android.content.Context
import com.ambient.tv.R

internal fun audioConversionFormats(sourceCodec: String?, sourceChannels: Int?, targetCodec: String?, targetChannels: Int?): String {
    fun format(codec: String?, channels: Int?) = listOfNotNull(
        codec?.let(::audioCodecLabel), channels?.takeIf { it > 0 }?.let(::audioChannelLabel),
    ).joinToString(" ").ifBlank { "?" }
    return "${format(sourceCodec, sourceChannels)} → ${format(targetCodec, targetChannels)}"
}

internal fun audioConversionDiagnostic(context: Context, state: PlayerUiState.Ready, session: AudioSession?,
    runtimeCodec: String? = null, runtimeChannels: Int? = null): String? {
    session?.activeLocalConversion?.let { return "${context.getString(R.string.audio_conversion_local)} · $it" }
    val target = state.serverAudioConversionCodec ?: return null
    val source = state.audioTracks.firstOrNull { it.id == state.serverAudioStreamId }
    // The requested maximum channel count is not evidence of the actual server output layout.
    val formats = audioConversionFormats(source?.codec, source?.channels, runtimeCodec ?: target, runtimeChannels)
    return "${context.getString(R.string.audio_conversion_server)} · $formats"
}
