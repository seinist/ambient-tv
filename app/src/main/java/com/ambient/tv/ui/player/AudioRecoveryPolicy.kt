package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioOptions
import com.ambient.tv.data.local.AudioSettings

/** Immutable user intent, independent of the released player's decoder and error state. */
internal data class AudioConversionRestart(
    val settings: AudioSettings,
    val titleDelayMs: Int?,
    val streamId: Long?,
    val absolutePositionMs: Long,
    val wasServerConverted: Boolean,
    val performanceHint: Boolean,
    val showFpsOverlay: Boolean,
) {
    fun restore(session: AudioSession) {
        session.restoreProfile(settings)
        session.restoreTitleDelay(titleDelayMs)
        session.performanceHint = performanceHint
        session.showFpsOverlay = showFpsOverlay
    }
}

internal fun AudioSession.conversionRestart(
    streamId: Long?, positionMs: Long, timelineOffsetMs: Long, wasServerConverted: Boolean,
): AudioConversionRestart = AudioConversionRestart(settings, titleDelayMs, streamId,
    (positionMs + timelineOffsetMs).coerceAtLeast(0L), wasServerConverted, performanceHint, showFpsOverlay)

/** Only transport edits need a new server URL; DSP/delay edits remain local. */
internal fun audioConversionRequiresReload(old: AudioOptions, next: AudioOptions): Boolean =
    old.conversionLocation != next.conversionLocation ||
        ((old.conversionLocation == "server" || next.conversionLocation == "server") &&
            (old.localAc3 != next.localAc3 || old.pcmChannelLimit != next.pcmChannelLimit ||
                old.output != next.output || old.channels != next.channels ||
                old.allowedCodecs != next.allowedCodecs || old.localFallback != next.localFallback))

/** Server conversion is an explicit preference, independent of the video renderer. */
internal fun shouldConvertAudioOnServer(
    options: AudioOptions, sourceCodec: String?, sourceChannels: Int?,
    route: AudioRouteCapabilities, sourceSupported: Boolean,
): Boolean = options.conversionLocation == "server" && options.localFallback != "off" &&
    (!sourceSupported || ((sourceChannels ?: 0) > 2 && normalizeSourceCodec(sourceCodec)?.let { it !in route.passthrough } == true))

internal fun serverAudioTarget(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?, sourcePassthrough: Boolean = false): String =
    if (options.transcodesAc3(sourcePassthrough, sourceChannels, route.pcmChannels,
        "ac3" in route.passthrough && "ac3" in options.allowedCodecs && "ac3" !in AudioCodecRejects.snapshot())) "ac3" else "aac"
