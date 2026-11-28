package com.ambient.tv.ui.moonlight

import com.ambient.tv.moonlight.MoonlightStreamProfile
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import kotlin.math.abs

/** Resolution/Hz selection for encoded streams, never for the remote game's render resolution. */
internal fun moonlightDisplayModes(
    modes: List<SupportedDisplayMode>, original: SupportedDisplayMode, handheld: Boolean,
    allowed: Set<String> = emptySet(), requiredHdrType: Int? = null,
): List<SupportedDisplayMode> = modes.filter {
    it.width > 0 && it.height > 0 && it.refreshRate.isFinite() && it.refreshRate > 0f &&
        (!handheld || (it.width == original.width && it.height == original.height)) &&
        (allowed.isEmpty() || it.key in allowed) &&
        (requiredHdrType == null || it.hdrTypes == null || requiredHdrType in it.hdrTypes)
}.distinctBy { it.key }

internal fun selectMoonlightDisplayMode(
    profile: MoonlightStreamProfile, modes: List<SupportedDisplayMode>, original: SupportedDisplayMode,
    handheld: Boolean, allowed: Set<String> = emptySet(), requiredHdrType: Int? = null,
): SupportedDisplayMode? {
    if (profile.displayOutputMode == MoonlightStreamProfile.DISPLAY_SYSTEM && !profile.matchDisplayRefresh) return null
    val available = moonlightDisplayModes(modes, original, handheld, allowed, requiredHdrType)
    if (MoonlightStreamProfile.isFixedDisplayMode(profile.displayOutputMode))
        return available.firstOrNull { it.key == profile.displayOutputMode }
    val bySize = available.groupBy { it.width to it.height }.values.mapNotNull { sameSize ->
        if (profile.matchDisplayRefresh) {
            // No video pulldown for interactive content. 1x, or a supported 2x cadence.
            DisplayRefreshRateMatcher.selectCadenceMode(sameSize, profile.fps.toFloat(),
                double = true, threeTwo = false, highDouble = true, preferDouble = profile.preferDoubleDisplayRefresh)
        } else sameSize.minByOrNull { abs(it.refreshRate - original.refreshRate) }
            ?.takeIf { abs(it.refreshRate - original.refreshRate) <= 0.6f }
    }
    val same = bySize.firstOrNull { it.width == original.width && it.height == original.height }
    val size = compareBy<SupportedDisplayMode>({ it.width.toLong() * it.height }, { it.width }, { it.modeId })
    if (handheld) return same
    return when (profile.displayOutputMode) {
        MoonlightStreamProfile.DISPLAY_SYSTEM -> same
        MoonlightStreamProfile.DISPLAY_FULL -> bySize.maxWithOrNull(size)
        MoonlightStreamProfile.DISPLAY_STREAM -> bySize.filter { it.width >= profile.width && it.height >= profile.height }
            .minWithOrNull(size) ?: bySize.maxWithOrNull(size)
        MoonlightStreamProfile.DISPLAY_PERFORMANCE -> bySize.filter {
            maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) <= 1080
        }.maxWithOrNull(size) ?: same
        else -> null
    }
}

internal fun moonlightDisplayRequested(profile: MoonlightStreamProfile) =
    profile.matchDisplayRefresh || profile.displayOutputMode != MoonlightStreamProfile.DISPLAY_SYSTEM
