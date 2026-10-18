package com.ambient.tv.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled._4k
import androidx.compose.material.icons.filled._8k
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import com.ambient.tv.ui.common.MetaBadge
import com.ambient.tv.ui.common.formatHdrMetadata
import com.ambient.tv.ui.display.LocalUiHdrState
import com.ambient.tv.ui.display.SdrToHdrStatus

/** Width preserves the quality tier of cinema material with cropped black bars. */
internal fun playerResolutionLabel(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val longEdge = maxOf(width, height)
    val shortEdge = minOf(width, height)
    return when {
        longEdge >= 7680 || shortEdge >= 4320 -> "8K"
        longEdge >= 3840 || shortEdge >= 2160 -> "4K"
        longEdge >= 2560 || shortEdge >= 1440 -> "QHD"
        longEdge >= 1920 || shortEdge >= 1080 -> "Full HD"
        longEdge >= 1280 || shortEdge >= 720 -> "HD"
        longEdge <= 1024 && shortEdge <= 576 -> "SD"
        else -> "${width}×${height}"
    }
}

internal fun playerDynamicRangeLabel(hdrLabel: String?, conversion: SdrToHdrStatus?): String =
    if (conversion == SdrToHdrStatus.ACTIVE) "SDR → HDR" else formatHdrMetadata(hdrLabel) ?: "SDR"

@Composable
internal fun PlayerVideoBadges(
    dimensions: String? = null,
    width: Int? = null,
    height: Int? = null,
    hdrLabel: String? = null,
    hasVideo: Boolean = false,
) {
    val size = DisplayRefreshRateMatcher.parseVideoDimensions(dimensions)
    val resolution = playerResolutionLabel(width ?: size?.first, height ?: size?.second)
    resolution?.let { label ->
        val icon = when (label) {
            "8K" -> Icons.Filled._8k
            "4K" -> Icons.Filled._4k
            "Full HD", "QHD" -> Icons.Filled.HighQuality
            "HD" -> Icons.Filled.Hd
            "SD" -> Icons.Filled.Sd
            else -> Icons.Filled.Tv
        }
        PlayerMediaFlag(icon, label, emphasized = label == "4K" || label == "8K")
    }
    if (!hasVideo && resolution == null && hdrLabel == null) return
    val range = playerDynamicRangeLabel(hdrLabel, LocalUiHdrState.current?.sdrToHdrStatus)
    val rangeIcon = when (range) {
        "SDR → HDR" -> Icons.AutoMirrored.Filled.ArrowForward
        "SDR" -> Icons.Filled.Tonality
        else -> Icons.Filled.HdrOn
    }
    PlayerMediaFlag(rangeIcon, range, emphasized = range != "SDR")
}

/** Shared presentation for Live TV and YouTube, including their combined Media3 labels. */
@Composable
internal fun PlayerStreamBadges(values: List<String>) {
    val parts = playerStreamBadgeParts(values)
    PlayerVideoBadges(dimensions = parts.dimensions, hdrLabel = parts.hdr)
    parts.extras.forEach { MetaBadge(it.uppercase()) }
}

internal const val PLAYER_STREAM_EXTRA_BADGES = 4

internal data class PlayerStreamBadgeParts(val dimensions: String?, val hdr: String?, val extras: List<String>)

/** Size and HDR get their own flags; at most [PLAYER_STREAM_EXTRA_BADGES] other labels follow. */
internal fun playerStreamBadgeParts(values: List<String>): PlayerStreamBadgeParts {
    val parts = values.flatMap { it.split('·') }.map(String::trim).filter(String::isNotBlank).distinct()
    val dimensions = parts.firstOrNull { DisplayRefreshRateMatcher.parseVideoDimensions(it) != null }
    val hdr = parts.firstOrNull { formatHdrMetadata(it) != null }
    return PlayerStreamBadgeParts(dimensions, hdr, parts.filter { it != dimensions && it != hdr }.take(PLAYER_STREAM_EXTRA_BADGES))
}
