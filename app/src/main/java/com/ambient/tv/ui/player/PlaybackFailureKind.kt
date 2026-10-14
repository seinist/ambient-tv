package com.ambient.tv.ui.player

enum class PlaybackFailureKind { AUDIO, VIDEO, UNKNOWN }

/** An unspecified decoder failure is not evidence that the video needs transcoding. */
fun playbackFailureKind(rendererName: String?, mimeType: String?, message: String?): PlaybackFailureKind {
    val renderer = rendererName.orEmpty().lowercase()
    val mime = mimeType.orEmpty().lowercase()
    return when {
        mime.startsWith("audio/") || "audio" in renderer -> PlaybackFailureKind.AUDIO
        mime.startsWith("video/") || "video" in renderer -> PlaybackFailureKind.VIDEO
        "audio" in message.orEmpty().lowercase() -> PlaybackFailureKind.AUDIO
        "video" in message.orEmpty().lowercase() -> PlaybackFailureKind.VIDEO
        else -> PlaybackFailureKind.UNKNOWN
    }
}
