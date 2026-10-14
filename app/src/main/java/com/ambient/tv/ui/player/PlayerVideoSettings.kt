package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.backend.VideoAccelerationCapabilities

/** Output overrides belong to one playback, not to global preferences. */
data class PlayerVideoSettings(
    val mpvOutput: String = "auto",
    val mpvDecoder: String = "auto",
    val vlcOutput: String = "auto",
) {
    fun withMpvOutput(output: String) = copy(
        mpvOutput = output,
        mpvDecoder = if (mpvDecoder == "vulkan" && output !in setOf("auto", "vulkan")) "auto" else mpvDecoder,
    )

    fun save(preferences: AppPreferences, backend: String) {
        when (backend) {
            "mpv" -> {
                preferences.mpvVideoOutputModeKey = mpvOutput
                preferences.mpvVideoDecoderKey = mpvDecoder
            }
            "vlc" -> preferences.vlcVideoOutputModeKey = vlcOutput
        }
    }

    companion object {
        fun from(preferences: AppPreferences) = PlayerVideoSettings(
            preferences.mpvVideoOutputModeKey, preferences.mpvVideoDecoderKey, preferences.vlcVideoOutputModeKey,
        )
    }
}

internal fun sameVideoSettingsPlayback(a: PlaybackMetadata?, b: PlaybackMetadata?): Boolean =
    a != null && b != null && a.route == b.route && a.source == b.source && a.mediaId == b.mediaId

internal fun disabledPlayerVideoOutputs(backend: String, caps: VideoAccelerationCapabilities,
    nativeHdrPreferred: Boolean = false): Set<String> =
    (if (if (backend == "mpv") caps.mpvVulkanOutput else caps.vlcVulkanOutput) emptySet() else setOf("vulkan")) +
        (if (nativeHdrPreferred) if (backend == "mpv") setOf("gpu", "vulkan") else setOf("vulkan") else emptySet())

internal fun disabledPlayerVideoDecoders(output: String, caps: VideoAccelerationCapabilities,
    nativeHdrPreferred: Boolean = false): Set<String> =
    if (!nativeHdrPreferred && caps.mpvVulkanDecode && output in setOf("auto", "vulkan")) emptySet() else setOf("vulkan")
