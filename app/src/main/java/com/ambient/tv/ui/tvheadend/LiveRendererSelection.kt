package com.ambient.tv.ui.tvheadend

import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings
import com.ambient.tv.ui.player.RendererCapabilityPolicy

/** The effective backend and the reason are separate from the user's saved choice. */
enum class LiveRendererOrigin { DEFAULT, CHANNEL, LIVE_PRESET, VIDEO_PRESET, MANUAL, RECOVERY, DOLBY_VISION, UNAVAILABLE }

internal data class LiveRendererSelection(val key: String, val origin: LiveRendererOrigin)

internal fun selectLiveRenderer(
    default: String,
    channel: TitleVideoSettings?,
    profiles: Map<String, ResolutionVideoProfile>,
    widthKey: String?,
    live: Boolean,
    manual: String? = null,
    recovery: String? = null,
    dolbyVision: Boolean = false,
    mpvAvailable: Boolean = true,
): LiveRendererSelection {
    val supported = setOf("auto", "media3", "vlc", "mpv")
    val liveProfile = if (live && widthKey != null) profiles["live:$widthKey"] else null
    val generalProfile = widthKey?.let(profiles::get)
    val selection = when {
        recovery in supported -> LiveRendererSelection(recovery!!, LiveRendererOrigin.RECOVERY)
        manual in supported -> LiveRendererSelection(manual!!, LiveRendererOrigin.MANUAL)
        channel?.rendererKey in supported -> LiveRendererSelection(channel!!.rendererKey!!, LiveRendererOrigin.CHANNEL)
        // Live Auto chooses Media3 first. Width presets still supply picture/audio options,
        // but must not silently opt a channel into MPV and its inherited filter chain.
        // An explicitly loaded preset arrives through manual; recovery requires an error.
        live && default == "auto" -> LiveRendererSelection("auto", LiveRendererOrigin.DEFAULT)
        liveProfile != null -> LiveRendererSelection(liveProfile.rendererOrDefault(default), LiveRendererOrigin.LIVE_PRESET)
        generalProfile != null -> LiveRendererSelection(generalProfile.rendererOrDefault(default), LiveRendererOrigin.VIDEO_PRESET)
        else -> LiveRendererSelection(default.takeIf { it in supported } ?: "auto", LiveRendererOrigin.DEFAULT)
    }
    val safe = RendererCapabilityPolicy.effectiveBackend(selection.key, null, null, null, detectedDolbyVision = dolbyVision)
    return when {
        safe != selection.key -> LiveRendererSelection(safe, LiveRendererOrigin.DOLBY_VISION)
        safe == "mpv" && !mpvAvailable -> LiveRendererSelection("media3", LiveRendererOrigin.UNAVAILABLE)
        else -> selection
    }
}
