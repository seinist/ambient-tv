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

/**
 * SmartTube and Monogram use MPV for their source-local Auto choice. The historical
 * global "media3" default is treated as Auto for these two sources only, but a
 * saved per-title or per-resolution choice still wins. Explicit VLC and MPV global
 * preferences are not replaced. Trailers and every other source keep the normal
 * global/Media3 behavior.
 */
internal fun sourceAutoDefaultRenderer(source: String, globalDefault: String, isTrailer: Boolean = false): String =
    if (!isTrailer && source in setOf("youtube", "telegram") && globalDefault in setOf("media3", "auto")) "auto"
    else globalDefault

/** Resolve the source's Auto to a concrete backend before acquiring a playback engine. */
internal fun resolveSourceAutoRenderer(
    source: String, requested: String, mpvAvailable: Boolean, isTrailer: Boolean = false,
): String = when {
    requested == "auto" && !isTrailer && source in setOf("youtube", "telegram") ->
        if (mpvAvailable) "mpv" else "media3"
    requested == "mpv" && !mpvAvailable -> "media3"
    requested == "auto" -> "media3"
    else -> requested
}

/** Priority: title > actual width preset > source-local Auto; a global pseudo-profile is not a width preset. */
internal fun preferredSourcePlaybackRenderer(
    source: String,
    globalDefault: String,
    title: TitleVideoSettings?,
    resolution: ResolutionVideoProfile?,
    mpvAvailable: Boolean,
    isTrailer: Boolean = false,
): String {
    if (source !in setOf("youtube", "telegram") || isTrailer) {
        return savedPlaybackRenderer(globalDefault, title, resolution, mpvAvailable)
    }
    val autoDefault = sourceAutoDefaultRenderer(source, globalDefault, isTrailer)
    val byResolution = resolution?.rendererOrDefault(autoDefault) ?: autoDefault
    val requested = title?.rendererOrDefault(byResolution) ?: byResolution
    return resolveSourceAutoRenderer(source, requested, mpvAvailable, isTrailer)
}
