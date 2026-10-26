package com.ambient.tv.data.remote

/** Codecs the app asks a media server to produce; AC3/E-AC3 are sent on as bitstream. */
internal val SERVER_AUDIO_TARGETS = setOf("ac3", "eac3", "aac")

/**
 * Channel ceiling for a server conversion. AAC follows the output width (2..8); AC3/E-AC3 are
 * capped at 5.1, the widest layout the server encoders produce.
 */
internal fun serverAudioChannelLimit(codec: String, channels: Int): Int =
    if (codec == "aac") channels.coerceIn(2, 8) else channels.coerceIn(2, 6)

internal fun serverAudioBitrate(codec: String, channels: Int): Int = when (codec) {
    "ac3", "eac3" -> 640_000
    else -> when {
        channels >= 8 -> 512_000
        channels >= 6 -> 384_000
        else -> 192_000
    }
}

/** Shared Emby/Jellyfin contract: copy video, convert the explicitly selected original audio. */
internal fun serverAudioConversionQuery(
    codec: String, video: Boolean, streamIndex: Int?, sessionId: String?,
    channels: Int = if (codec == "aac") 2 else 6,
): Map<String, String?> {
    require(codec in SERVER_AUDIO_TARGETS)
    val limit = serverAudioChannelLimit(codec, channels)
    return mapOf(
        "AudioCodec" to codec,
        "AudioStreamIndex" to streamIndex?.toString(),
        "AllowAudioStreamCopy" to "false",
        "AllowVideoStreamCopy" to (!video).toString(),
        "TranscodingMaxAudioChannels" to limit.toString(),
        "AudioBitrate" to serverAudioBitrate(codec, limit).toString(),
        "PlaySessionId" to sessionId,
    )
}
