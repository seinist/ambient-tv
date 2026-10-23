package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AudioOptions

/** Server conversion is an explicit preference, independent of the video renderer. */
internal fun shouldConvertAudioOnServer(
    options: AudioOptions, sourceCodec: String?, sourceChannels: Int?,
    route: AudioRouteCapabilities, sourceSupported: Boolean,
): Boolean = options.conversionLocation == "server" && options.localFallback != "off" &&
    (!sourceSupported || ((sourceChannels ?: 0) > 2 && normalizeSourceCodec(sourceCodec)?.let { it !in route.passthrough } == true))

internal fun serverAudioTarget(options: AudioOptions, route: AudioRouteCapabilities, sourceChannels: Int?, sourcePassthrough: Boolean = false): String =
    if (options.transcodesAc3(sourcePassthrough, sourceChannels, route.pcmChannels,
        "ac3" in route.passthrough && "ac3" in options.allowedCodecs && "ac3" !in AudioCodecRejects.snapshot())) "ac3" else "aac"
