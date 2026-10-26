package com.ambient.tv.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

/**
 * Lifts the AAC channel ceiling of a server-made Emby/Jellyfin transcoding URL to the output
 * width. Without a device profile the servers default to stereo; `<codec>-audiochannels`,
 * `AudioChannels`, `MaxAudioChannels` and `TranscodingMaxAudioChannels` all cap it. Stereo
 * outputs and unparsable URLs are returned unchanged.
 */
internal fun withServerAudioChannels(url: String, channels: Int): String {
    val limit = serverAudioChannelLimit("aac", channels)
    if (limit <= 2) return url
    val parsed = url.toHttpUrlOrNull() ?: return url
    val builder = parsed.newBuilder()
    parsed.queryParameterNames.forEach { name ->
        val key = name.lowercase()
        if (key.endsWith("-audiochannels") || key == "audiochannels") builder.setQueryParameter(name, limit.toString())
    }
    fun set(name: String) {
        val existing = parsed.queryParameterNames.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        builder.setQueryParameter(existing, limit.toString())
    }
    set("MaxAudioChannels")
    set("TranscodingMaxAudioChannels")
    val bitrateName = parsed.queryParameterNames.firstOrNull { it.equals("AudioBitrate", ignoreCase = true) }
    val bitrate = bitrateName?.let { parsed.queryParameter(it)?.toIntOrNull() }
    val wanted = serverAudioBitrate("aac", limit)
    if (bitrate == null || bitrate < wanted) builder.setQueryParameter(bitrateName ?: "AudioBitrate", wanted.toString())
    return builder.build().toString()
}
