package com.ambient.tv.ui.player

import com.ambient.tv.data.local.ResolutionVideoProfile
import com.ambient.tv.data.local.TitleVideoSettings

/** Specific video beats its resolution class, which beats the global setting. */
internal fun savedPlaybackRenderer(
    default: String, title: TitleVideoSettings?, resolution: ResolutionVideoProfile?, mpvAvailable: Boolean,
): String {
    val byResolution = resolution?.rendererOrDefault(default) ?: default
    val selected = title?.rendererOrDefault(byResolution) ?: byResolution
    return if (selected == "mpv" && !mpvAvailable) "media3" else selected
}
