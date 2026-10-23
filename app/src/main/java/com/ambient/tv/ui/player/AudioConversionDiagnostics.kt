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
    session?.let { audioDownmixLabel(it.sourceChannels, it.outputPlan()) }?.let { return it }
    session?.activeLocalConversion?.let { return "${context.getString(R.string.audio_conversion_local)} · $it" }
    val target = state.serverAudioConversionCodec ?: return null
    val source = state.audioTracks.firstOrNull { it.id == state.serverAudioStreamId }
    // The requested maximum channel count is not evidence of the actual server output layout.
    val formats = audioConversionFormats(source?.codec, source?.channels, runtimeCodec ?: target, runtimeChannels)
    return "${context.getString(R.string.audio_conversion_server)} · $formats"
}

/**
 * Short OSD chip for what happens to the sound on its way out, or null for an ordinary
 * decode (AAC, Opus, FLAC … to PCM). An actual local conversion wins over the plan because
 * it names both formats ("DTS-HD 7.1 → DTS 5.1", "E-AC3 5.1 → PCM 5.1").
 */
internal fun audioOutputBadgeLabel(
    localConversion: String?,
    serverTarget: String?,
    mode: AudioOutputMode?,
    sourceCodec: String?,
    passthroughLabel: String,
    dtsCoreLabel: String,
    serverLabel: String,
    downmix: String? = null,
): String? = when {
    downmix != null -> downmix
    localConversion != null -> localConversion
    serverTarget != null -> "$serverLabel → ${audioCodecLabel(serverTarget)}"
    mode == AudioOutputMode.PASSTHROUGH -> passthroughLabel
    mode == AudioOutputMode.DTS_CORE -> dtsCoreLabel
    // Bitstream codecs decoded by MPV/VLC/MediaCodec: say so, the receiver will not show it.
    mode == AudioOutputMode.PCM && normalizeSourceCodec(sourceCodec) != null -> "→ PCM"
    else -> null
}

@androidx.compose.runtime.Composable
internal fun audioOutputBadge(session: AudioSession?, serverTarget: String? = null): String? {
    if (session == null && serverTarget == null) return null
    return audioOutputBadgeLabel(
        downmix = session?.let { audioDownmixLabel(it.sourceChannels, it.outputPlan()) },
        localConversion = session?.activeLocalConversion,
        serverTarget = serverTarget,
        mode = session?.outputPlan()?.mode,
        sourceCodec = session?.sourceCodec,
        passthroughLabel = androidx.compose.ui.res.stringResource(R.string.diag_mode_passthrough),
        dtsCoreLabel = androidx.compose.ui.res.stringResource(R.string.audio_badge_dts_core),
        serverLabel = androidx.compose.ui.res.stringResource(R.string.audio_badge_server),
    )
}
