package com.ambient.tv.ui.player

/** Audio recovery can choose another local engine, never a server stream/profile. */
object LocalAudioFallbackPolicy {
    fun nextBackend(
        current: String,
        attempted: Set<String>,
        mpvAvailable: Boolean,
        enabled: Boolean = true,
        videoCodec: String? = null,
        dynamicRange: String? = null,
        videoProfile: String? = null,
    ): String? {
        if (!enabled) return null
        val active = if (current == "auto") "media3" else current
        return listOfNotNull("mpv".takeIf { mpvAvailable }, "vlc").firstOrNull { candidate ->
            candidate != active && candidate !in attempted &&
                RendererCapabilityPolicy.effectiveBackend(candidate, videoCodec, dynamicRange, videoProfile) == candidate
        }
    }
}
