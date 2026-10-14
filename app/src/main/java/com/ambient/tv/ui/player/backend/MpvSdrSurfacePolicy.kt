package com.ambient.tv.ui.player.backend

// Android's BT.709 transfer is distinct from its sRGB transfer. GPU output contains
// full-range RGB, whereas the direct MediaCodec YUV surface is limited-range.
internal const val MPV_BT709_FULL = (1 shl 16) or (3 shl 22) or (1 shl 27)

/** Preserve Direct's interpretation only for positively identified BT.709 SDR video. */
internal fun mpvSdrSurfaceDataSpace(
    vo: String?, sourceTransfer: String?, filteredTransfer: String?, primaries: String?,
    targetTransfer: String?, targetPrimaries: String?, converting: Boolean,
): Int? {
    if (converting || vo !in setOf("gpu", "gpu-next")) return null
    if (sourceTransfer != "bt.1886" || filteredTransfer != "bt.1886" || primaries != "bt.709") return null
    // Both renderers publish their actual target; never infer it from the source alone.
    if (targetTransfer != "bt.1886" || targetPrimaries != "bt.709") return null
    return MPV_BT709_FULL
}
