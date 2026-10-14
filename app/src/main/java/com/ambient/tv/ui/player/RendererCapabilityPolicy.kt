package com.ambient.tv.ui.player

/**
 * Keeps a user-requested renderer from opening a stream it cannot present correctly.
 *
 * libmpv/libVLC can decode the HEVC base layer of a Dolby Vision file on SHIELD while never
 * engaging the vendor Dolby Vision path. That failure looks deceptively healthy (audio and the
 * playback clock continue) but the video plane stays black. Media3 advertises the Dolby Vision
 * MIME type to MediaCodec and is therefore the safe renderer for these streams.
 */
object RendererCapabilityPolicy {
    private val supportedKeys = setOf("auto", "media3", "vlc", "mpv")

    fun effectiveBackend(
        requested: String?,
        videoCodec: String?,
        dynamicRange: String?,
        videoProfile: String?,
        detectedDolbyVision: Boolean = false,
    ): String {
        val normalized = requested?.takeIf(supportedKeys::contains) ?: "auto"
        val descriptor = listOfNotNull(videoCodec, dynamicRange, videoProfile)
            .joinToString(" ")
            .lowercase().replace('_', ' ')
        val dolbyVision = detectedDolbyVision || descriptor.contains("dolby vision") ||
            descriptor.contains("dolbyvision") ||
            descriptor.contains("dovi") ||
            descriptor.contains("dvhe") || descriptor.contains("dvh1") ||
            descriptor.contains("dvav") || descriptor.contains("dva1") ||
            dynamicRange?.trim()?.equals("dv", ignoreCase = true) == true

        return if (dolbyVision && normalized in setOf("vlc", "mpv")) "media3" else normalized
    }
}
