package com.ambient.tv.ui.player.backend

import com.ambient.tv.data.local.AppPreferences

// Android's BT.709 transfer is distinct from its sRGB transfer. GPU output contains
// full-range RGB, whereas the direct MediaCodec YUV surface is limited-range.
internal const val MPV_BT709_FULL = (1 shl 16) or (3 shl 22) or (1 shl 27)
/** ADataSpace STANDARD_BT709 | TRANSFER_GAMMA2_2 | RANGE_FULL: decoded by SurfaceFlinger as pure gamma 2.2. */
internal const val MPV_BT709_GAMMA22_FULL = (1 shl 16) or (4 shl 22) or (1 shl 27)
/** Every dataspace this player may have written; a restore only replaces its own tag. */
internal val MPV_SDR_SURFACE_DATASPACES = setOf(MPV_BT709_FULL, MPV_BT709_GAMMA22_FULL)

/**
 * mpv target-trc for SDR GPU output ("SDR-Kurve"). The surface tag follows the same curve
 * ([mpvSdrSurfaceDataSpace]) so SurfaceFlinger decodes exactly what mpv encoded.
 *
 * - BT.1886: mpv encodes with BT.1886 (gamma 2.4), tagged SMPTE 170M like the direct MediaCodec
 *   surface. On an SDR panel that SurfaceFlinger passes through, this matches Direct.
 * - Gamma 2.2: mpv encodes with pure 2.2, tagged TRANSFER_GAMMA2_2. When SurfaceFlinger composes
 *   SDR into an HDR/PQ output it linearises the 170M tag with the BT.709 camera curve, which
 *   lifts BT.1886 midtones and blacks (washed out); a 2.2/2.2 pair round-trips exactly.
 * - Auto: Gamma 2.2 on a display with HDR output capabilities (SDR is composed/tone-mapped
 *   there), otherwise BT.1886.
 */
internal fun mpvSdrCurveTransfer(key: String, hdrCapableDisplay: Boolean): String = when (key) {
    AppPreferences.MPV_SDR_CURVE_GAMMA22 -> "gamma2.2"
    AppPreferences.MPV_SDR_CURVE_BT1886 -> "bt.1886"
    else -> if (hdrCapableDisplay) "gamma2.2" else "bt.1886"
}

/**
 * Tag only positively identified BT.709 SDR video (or HDR that mpv tone-mapped to SDR,
 * [toneMappedToSdr]) and only with the curve mpv actually published as its target.
 */
internal fun mpvSdrSurfaceDataSpace(
    vo: String?, sourceTransfer: String?, filteredTransfer: String?, primaries: String?,
    targetTransfer: String?, targetPrimaries: String?, converting: Boolean,
    toneMappedToSdr: Boolean = false,
): Int? {
    if (converting || vo !in setOf("gpu", "gpu-next")) return null
    if (!toneMappedToSdr && (sourceTransfer != "bt.1886" || filteredTransfer != "bt.1886" || primaries != "bt.709")) return null
    // Both renderers publish their actual target; never infer it from the source alone.
    if (targetPrimaries != "bt.709") return null
    return when (targetTransfer) {
        "bt.1886" -> MPV_BT709_FULL
        "gamma2.2" -> MPV_BT709_GAMMA22_FULL
        else -> null
    }
}

/** Stats label for the tag in use. */
internal fun mpvSdrSurfaceLabel(dataSpace: Int?): String = when (dataSpace) {
    MPV_BT709_FULL -> "BT.709 / BT.1886 (170M) full"
    MPV_BT709_GAMMA22_FULL -> "BT.709 / Gamma 2.2 full"
    else -> "untagged"
}
