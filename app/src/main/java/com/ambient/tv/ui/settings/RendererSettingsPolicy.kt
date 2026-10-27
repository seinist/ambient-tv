package com.ambient.tv.ui.settings

import com.ambient.tv.R
import com.ambient.tv.ui.player.backend.PlaybackBackendResolver
import com.ambient.tv.ui.player.backend.VideoAccelerationCapabilities

/** Auto plays with the renderer [PlaybackBackendResolver] picks, so its controls are the ones that apply. */
internal fun selectedRendererSettingsKey(key: String): String =
    key.takeIf { it in setOf("media3", "vlc", "mpv") } ?: PlaybackBackendResolver.resolve(key).key

internal fun vulkanApiLabel(version: Long): String =
    "${(version shr 22) and 127}.${(version shr 12) and 1023}.${version and 4095}"

/** These are preflight failures, not evidence of successful video playback. */
internal fun vulkanOutputProblem(backend: String, caps: VideoAccelerationCapabilities): Int? = when {
    !caps.checked -> R.string.vulkan_checking
    backend !in setOf("mpv", "vlc") -> R.string.renderer_media3_hint
    !caps.device.render && caps.device.reason == "probe_unavailable" -> R.string.vulkan_probe_failed
    !(if (backend == "mpv") caps.mpvOutputBuilt else caps.vlcOutputBuilt) -> R.string.vulkan_build_unavailable
    !caps.device.render -> when (caps.device.reason) {
        "timeline_semaphore" -> R.string.vulkan_missing_timeline
        "loader" -> R.string.vulkan_missing_loader
        "android_surface", "native_window", "surface_create", "present_queue", "surface_formats" -> R.string.vulkan_surface_unavailable
        "vulkan_1_1" -> R.string.vulkan_api_1_1
        "probe_unavailable" -> R.string.vulkan_probe_failed
        "logical_device" -> R.string.vulkan_device_creation_failed
        else -> R.string.vulkan_device_unavailable
    }
    backend == "mpv" && caps.device.version < ((1L shl 22) or (2L shl 12)) -> R.string.vulkan_mpv_api
    backend == "mpv" && !caps.device.hostQueryReset -> R.string.vulkan_missing_host_query
    else -> null
}
