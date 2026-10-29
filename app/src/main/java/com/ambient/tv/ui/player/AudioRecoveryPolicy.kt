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
    val showFrameDropOverlay: Boolean,
    val showFrameDropHistory: Boolean,
) {
    fun restore(session: AudioSession) {
        session.restoreProfile(settings)
        session.restoreTitleDelay(titleDelayMs)
        session.performanceHint = performanceHint
        session.showFpsOverlay = showFpsOverlay
        session.showFrameDropOverlay = showFrameDropOverlay
        session.showFrameDropHistory = showFrameDropHistory
    }
}

internal fun AudioSession.conversionRestart(
    streamId: Long?, positionMs: Long, timelineOffsetMs: Long, wasServerConverted: Boolean,
): AudioConversionRestart = AudioConversionRestart(settings, titleDelayMs, streamId,
    (positionMs + timelineOffsetMs).coerceAtLeast(0L), wasServerConverted, performanceHint, showFpsOverlay,
    showFrameDropOverlay, showFrameDropHistory)

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

/**
 * The options a server conversion has to respect in the current playback: after a rejected
 * forced 5.1/7.1 the output follows the route again, and after a failed multichannel or
 * bitstream output only stereo PCM is left. The saved profile is not changed.
 */
internal fun serverConversionOptions(options: AudioOptions, forceStereoPcm: Boolean, forcedChannelsFailed: Boolean): AudioOptions =
    when {
        forceStereoPcm -> options.copy(output = "pcm", forceChannels = 0, pcmChannelLimit = 2, localAc3 = false)
        forcedChannelsFailed -> options.withoutForcing().copy(
            pcmChannelLimit = options.pcmChannelLimit.takeIf { it == 2 || it != options.forceChannels } ?: 0)
        else -> options
    }

internal fun serverAudioTarget(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?, sourcePassthrough: Boolean = false): String {
    val forced = route.forcedBy(options)
    val rejects = AudioCodecRejects.snapshot()
    fun confirmed(codec: String) = codec in forced.passthrough && codec in options.allowedCodecs && codec !in rejects
    val source = sourceChannels ?: 0
    val pcm = options.pcmChannels(route.pcmChannels)
    val surroundMix = options.channels !in STEREO_MIXES
    val encodedAllowed = options.localFallback == "auto" && options.permitsBitstream(sourcePassthrough) && surroundMix
    // Multichannel source, only stereo PCM, but the output confirms AC3: the server sends
    // 5.1 AC3 instead of a stereo downmix (the server path needs no separate AC3 switch).
    if (encodedAllowed && source > 2 && pcm <= 2 && confirmed("ac3")) return "ac3"
    // Multichannel source, PCM output narrower than the source, but the receiver confirms E-AC3:
    // let the server send 5.1 E-AC3 instead of a downmix.
    if (encodedAllowed && source > 2 && pcm < minOf(source, 6) && confirmed("eac3")) return "eac3"
    return "aac"
}

private val STEREO_MIXES = setOf("stereo", "mono", "left", "right")

/** Codecs from [serverAudioTarget] that leave the device as bitstream. */
internal fun serverAudioTargetIsEncoded(codec: String?): Boolean = codec == "ac3" || codec == "eac3"

/**
 * Channel ceiling sent with a server conversion: the output's PCM width (raised by "Kanäle
 * erzwingen", lowered by the PCM limit), never more than the source. AC3/E-AC3 always ask for 5.1.
 */
internal fun serverAudioChannels(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?, codec: String): Int {
    if (serverAudioTargetIsEncoded(codec)) return 6
    if (options.channels in STEREO_MIXES) return 2
    val pcm = options.pcmChannels(route.pcmChannels).coerceIn(2, 8)
    val source = sourceChannels?.takeIf { it > 0 } ?: return pcm
    return minOf(pcm, source).coerceAtLeast(2)
}

/**
 * Audio channels for the full H.264/AAC video fallback: the same width as an audio-only server
 * conversion, so a 5.1/7.1 output keeps surround instead of a forced stereo mix.
 */
internal fun videoFallbackAudioChannels(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?): Int =
    serverAudioChannels(options, route, sourceChannels, "aac")
