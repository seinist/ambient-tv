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
            (old.localAc3 != next.localAc3 || old.pcmChannelLimit != next.pcmChannelLimit || old.forceChannels != next.forceChannels ||
                old.output != next.output || old.channels != next.channels ||
                old.allowedCodecs != next.allowedCodecs || old.localFallback != next.localFallback))

/** Server conversion is an explicit preference, independent of the video renderer. */
internal fun shouldConvertAudioOnServer(
    options: AudioOptions, sourceCodec: String?, sourceChannels: Int?,
    route: AudioRouteCapabilities, sourceSupported: Boolean,
): Boolean = options.conversionLocation == "server" && options.localFallback != "off" &&
    (!sourceSupported || ((sourceChannels ?: 0) > 2 && normalizeSourceCodec(sourceCodec)?.let { it !in route.passthrough } == true))

internal fun serverAudioTarget(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?, sourcePassthrough: Boolean = false): String {
    val forced = route.forcedBy(options)
    val rejects = AudioCodecRejects.snapshot()
    fun confirmed(codec: String) = codec in forced.passthrough && codec in options.allowedCodecs && codec !in rejects
    if (options.transcodesAc3(sourcePassthrough, sourceChannels, route.pcmChannels, confirmed("ac3"))) return "ac3"
    // Multichannel source, PCM output narrower than the source, but the receiver confirms E-AC3:
    // let the server send 5.1 E-AC3 instead of a downmix.
    val source = sourceChannels ?: 0
    val pcm = options.pcmChannels(route.pcmChannels)
    if (source > 2 && pcm < minOf(source, 6) && options.localFallback == "auto" &&
        options.permitsBitstream(sourcePassthrough) && confirmed("eac3")) return "eac3"
    return "aac"
}

/** Codecs from [serverAudioTarget] that leave the device as bitstream. */
internal fun serverAudioTargetIsEncoded(codec: String?): Boolean = codec == "ac3" || codec == "eac3"

/**
 * Channel ceiling sent with a server conversion: the output's PCM width (raised by "Kanäle
 * erzwingen", lowered by the PCM limit), never more than the source. AC3/E-AC3 always ask for 5.1.
 */
internal fun serverAudioChannels(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?, codec: String): Int {
    if (serverAudioTargetIsEncoded(codec)) return 6
    val pcm = options.pcmChannels(route.pcmChannels).coerceIn(2, 8)
    val source = sourceChannels?.takeIf { it > 0 } ?: return pcm
    return minOf(pcm, source).coerceAtLeast(2)
}
