package com.ambient.tv.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fit cinematic strips inside the *actual* layout viewport, not the physical display's height.
 * UI scale, overscan and app header sizes differ between Shield and TV firmware.
 * Roomy screens keep their original card widths; only compact viewports reduce them.
 */
internal fun libraryCinematicTileWidth(viewportHeight: Dp, poster: Boolean, preferredWidth: Dp): Dp {
    val reservedHero = maxOf(
        if (poster) 110.dp else 120.dp,
        viewportHeight * (if (poster) 0.30f else 0.33f),
    )
    // Tile row top/bottom padding and its preceding spacer. Landscape cards also have
    // title/metadata below the artwork and a 16dp focus gutter.
    val rowChrome = if (poster) 54.dp else 122.dp
    val availableArtworkHeight = (viewportHeight - reservedHero - rowChrome).coerceAtLeast(0.dp)
    return minOf(preferredWidth, availableArtworkHeight * (if (poster) 2f / 3f else 16f / 9f))
}

/** SmartTube cinematic cards are thumbnail-only, with no text below the artwork. */
internal fun youtubeCinematicTileWidth(viewportHeight: Dp, preferredWidth: Dp): Dp {
    val reservedMetadata = maxOf(75.dp, viewportHeight * 0.30f)
    val availableArtworkHeight = (viewportHeight - reservedMetadata - 52.dp).coerceAtLeast(0.dp)
    return minOf(preferredWidth, availableArtworkHeight * (16f / 9f))
}
