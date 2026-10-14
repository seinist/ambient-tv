package com.ambient.tv.domain.playback

import com.ambient.tv.domain.model.MediaVersion

enum class PlaybackMode {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
}

data class PlaybackPlan(
    val mode: PlaybackMode,
    val url: String,
    val reason: String,
)

/** Direct Play is always the first-choice policy. Fallback decisions are explicit. */
object PlaybackPlanner {
    fun directPlay(version: MediaVersion): PlaybackPlan? {
        val url = version.streamUrl ?: return null
        return PlaybackPlan(
            mode = PlaybackMode.DIRECT_PLAY,
            url = url,
            reason = "Original media selected; server transcoding not requested",
        )
    }

    fun audioFallback(url: String, sourceAudioCodec: String?, targetAudioCodec: String = "aac"): PlaybackPlan = PlaybackPlan(
        mode = PlaybackMode.DIRECT_STREAM,
        url = url,
        reason = "Video kept eligible for stream-copy; unsupported ${sourceAudioCodec ?: "audio"} converted to ${targetAudioCodec.uppercase()}",
    )

    fun videoFallback(url: String, sourceVideoCodec: String?): PlaybackPlan = PlaybackPlan(
        mode = PlaybackMode.TRANSCODE,
        url = url,
        reason = "Unsupported ${sourceVideoCodec ?: "video"}; server fallback uses H.264 video and AAC audio",
    )
}
