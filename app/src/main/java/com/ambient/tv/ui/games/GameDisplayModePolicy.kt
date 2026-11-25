package com.ambient.tv.ui.games

import com.ambient.tv.games.GameDisplayProfile
import com.ambient.tv.ui.player.DisplayRefreshRateMatcher
import com.ambient.tv.ui.player.SupportedDisplayMode
import kotlin.math.abs

/** Nominal core rate, not measured render throughput or fast-forward rate. */
internal data class GameDisplayFormat(val fps: Float?, val frame: GameContentFrame?)

internal fun gameDisplayModes(
    modes: List<SupportedDisplayMode>, baseline: SupportedDisplayMode, handheld: Boolean,
    whitelist: Set<String> = emptySet(), requiredHdrType: Int? = null,
): List<SupportedDisplayMode> = modes.filter {
    it.width > 0 && it.height > 0 && it.refreshRate.isFinite() && it.refreshRate > 0f &&
        (!handheld || (it.width == baseline.width && it.height == baseline.height)) &&
        (whitelist.isEmpty() || it.key in whitelist) &&
        (requiredHdrType == null || it.hdrTypes == null || requiredHdrType in it.hdrTypes)
}.distinctBy { it.key }

/**
 * Games never use video pulldown or change the core clock. First require a suitable cadence,
 * then choose an output size. An explicitly fixed display mode is an exact user request.
 * Unknown rates/unsupported modes do not trigger speculative HDMI changes.
 */
internal fun selectGameDisplayMode(
    modes: List<SupportedDisplayMode>, baseline: SupportedDisplayMode, format: GameDisplayFormat,
    profile: GameDisplayProfile, handheld: Boolean, whitelist: Set<String> = emptySet(),
    requiredHdrType: Int? = null,
): SupportedDisplayMode? {
    if (!profile.active) return null
    val available = gameDisplayModes(modes, baseline, handheld, whitelist, requiredHdrType)
    if (GameDisplayProfile.isFixed(profile.resolution)) return available.firstOrNull { it.key == profile.resolution }
    val fps = format.fps?.takeIf { it.isFinite() && it in 1f..240f }
    if (profile.matchRate && fps == null) return null
    val candidates = available.groupBy { it.width to it.height }.values.mapNotNull { sameSize ->
        if (profile.matchRate && fps != null) {
            DisplayRefreshRateMatcher.selectCadenceMode(
                sameSize, fps, double = true, threeTwo = false,
                highDouble = true, preferDouble = profile.preferDouble,
            )
        } else {
            sameSize.minWithOrNull(compareBy<SupportedDisplayMode>({ abs(it.refreshRate - baseline.refreshRate) }, { it.modeId }))
                ?.takeIf { abs(it.refreshRate - baseline.refreshRate) <= 0.6f }
        }
    }
    val original = candidates.firstOrNull { it.width == baseline.width && it.height == baseline.height }
    val size = compareBy<SupportedDisplayMode>({ it.width.toLong() * it.height }, { it.width }, { it.modeId })
    if (handheld) return original
    return when (profile.resolution) {
        GameDisplayProfile.SYSTEM -> original
        GameDisplayProfile.DISPLAY -> candidates.maxWithOrNull(size)
        GameDisplayProfile.PERFORMANCE -> candidates.filter {
            maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) <= 1080
        }.maxWithOrNull(size) ?: original
        GameDisplayProfile.GAME -> {
            val source = format.frame ?: return original
            // Retro pixels are not a useful physical TV mode. Keep at least HD if available.
            val landscape = baseline.width >= baseline.height
            val minWidth = maxOf(source.width, if (landscape) 1280 else 720)
            val minHeight = maxOf(source.height, if (landscape) 720 else 1280)
            candidates.filter { it.width >= minWidth && it.height >= minHeight }
                .minWithOrNull(size) ?: original ?: candidates.maxWithOrNull(size)
        }
        else -> null
    }
}
