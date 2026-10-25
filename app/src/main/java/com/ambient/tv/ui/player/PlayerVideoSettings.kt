package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.player.backend.VideoAccelerationCapabilities

/** Output overrides belong to one playback, not to global preferences. */
data class PlayerVideoSettings(
    val mpvOutput: String = "auto",
    val mpvDecoder: String = "auto",
    val vlcOutput: String = "auto",
    /**
     * [mpvOutput] "direct" was set by the player itself (HDR found at runtime, the GPU route
     * does not apply), not chosen by the user: it routes this playback but is neither an
     * explicit choice for "HDR über GPU" nor stored for the title or a profile.
     */
    val automaticHdrDirect: Boolean = false,
) {
    /** A user's output choice; it replaces an automatic "direct". */
    fun withMpvOutput(output: String) = copy(
        mpvOutput = output,
        mpvDecoder = if (mpvDecoder == "vulkan" && output !in setOf("auto", "vulkan")) "auto" else mpvDecoder,
        automaticHdrDirect = false,
    )

    /** The player's own HDR fallback to the direct surface ([automaticHdrDirect]). */
    fun withAutomaticHdrDirect() = withMpvOutput("direct").copy(automaticHdrDirect = true)

    /** What the user actually chose: an automatic "direct" falls back to [configured]'s output. */
    fun chosen(configured: PlayerVideoSettings) =
        if (automaticHdrDirect) copy(mpvOutput = configured.mpvOutput, automaticHdrDirect = false) else this

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
    nativeHdrPreferred: Boolean = false, hdrGpu: Boolean = false): Set<String> =
    (if (if (backend == "mpv") caps.mpvVulkanOutput else caps.vlcVulkanOutput) emptySet() else setOf("vulkan")) +
        (if (nativeHdrPreferred) if (backend == "mpv") setOf("gpu", "vulkan") else setOf("vulkan") else emptySet()) +
        // HDR über GPU on Vulkan: plain OpenGL cannot signal HDR; every GPU choice plays through Vulkan.
        // (The EGL PQ route passes hdrGpu=false: there OpenGL is the HDR output.)
        (if (hdrGpu && backend == "mpv") setOf("gpu") else emptySet())

internal fun disabledPlayerVideoDecoders(output: String, caps: VideoAccelerationCapabilities,
    nativeHdrPreferred: Boolean = false): Set<String> =
    if (!nativeHdrPreferred && caps.mpvVulkanDecode && output in setOf("auto", "vulkan")) emptySet() else setOf("vulkan")
