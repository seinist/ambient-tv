package com.ambient.tv.ui.player

private val supportedRendererKeys = setOf("auto", "media3", "vlc", "mpv")

/** The same restrictions govern playback routing and explicit choices in the player panel. */
internal data class RendererAvailability(val dolbyVision: Boolean, val mpvAvailable: Boolean) {
    val disabledKeys: Set<String> = buildSet {
        if (dolbyVision) addAll(setOf("vlc", "mpv"))
        if (!mpvAvailable) add("mpv")
    }

    fun canSelect(key: String): Boolean = key in supportedRendererKeys && key !in disabledKeys
}

/**
 * Keeps a user-requested renderer from opening a stream it cannot present correctly.
 *
 * libmpv/libVLC can decode the HEVC base layer of a Dolby Vision file on SHIELD while never
 * engaging the vendor Dolby Vision path. That failure looks deceptively healthy (audio and the
 * playback clock continue) but the video plane stays black. Media3 advertises the Dolby Vision
 * MIME type to MediaCodec and is therefore the safe renderer for these streams.
 */
object RendererCapabilityPolicy {
    fun effectiveBackend(
        requested: String?,
        videoCodec: String?,
        dynamicRange: String?,
        videoProfile: String?,
        detectedDolbyVision: Boolean = false,
        videoTranscoded: Boolean = false,
    ): String {
        val normalized = requested?.takeIf(supportedRendererKeys::contains) ?: "auto"
        val dolbyVision = isDolbyVision(videoCodec, dynamicRange, videoProfile, detectedDolbyVision, videoTranscoded)
        return if (dolbyVision && normalized in setOf("vlc", "mpv")) "media3" else normalized
    }

    fun isDolbyVision(
        videoCodec: String?,
        dynamicRange: String?,
        videoProfile: String?,
        detectedDolbyVision: Boolean = false,
        videoTranscoded: Boolean = false,
    ): Boolean {
        // A full video transcode replaces the original video. Audio conversion/direct stream
        // still copies it; only confirmed video conversion may discard the source descriptors.
        if (detectedDolbyVision) return true
        if (videoTranscoded) return false
        val descriptor = listOfNotNull(videoCodec, dynamicRange, videoProfile)
            .joinToString(" ")
            .lowercase().replace('_', ' ').replace('-', ' ')
        return descriptor.contains("dolby vision") ||
            descriptor.contains("dolbyvision") ||
            descriptor.contains("dovi") ||
            descriptor.contains("dvhe") || descriptor.contains("dvh1") ||
            descriptor.contains("dvav") || descriptor.contains("dva1") || descriptor.contains("dav1") ||
            dynamicRange?.trim()?.equals("dv", ignoreCase = true) == true
    }
}
