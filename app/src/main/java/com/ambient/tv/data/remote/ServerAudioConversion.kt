package com.ambient.tv.data.remote

/** Shared Emby/Jellyfin contract: copy video, convert the explicitly selected original audio. */
internal fun serverAudioConversionQuery(codec: String, video: Boolean, streamIndex: Int?, sessionId: String?): Map<String, String?> {
    require(codec in setOf("ac3", "aac"))
    return mapOf(
        "AudioCodec" to codec,
        "AudioStreamIndex" to streamIndex?.toString(),
        "AllowAudioStreamCopy" to "false",
        "AllowVideoStreamCopy" to (!video).toString(),
        "TranscodingMaxAudioChannels" to if (codec == "ac3") "6" else "2",
        "AudioBitrate" to if (codec == "ac3") "640000" else "192000",
        "PlaySessionId" to sessionId,
    )
}
