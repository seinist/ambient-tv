package com.ambient.tv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp

/**
 * Accent material is independent of the chosen accent hue and playback accent.
 * Light intentionally preserves the previous solid-color look. All other styles
 * use static base brushes; optional, Maximum-only material motion is drawn separately.
 */
enum class AccentStyle(val key: String) {
    SATIN("satin"),
    GLASS("glass"),
    GLOW("glow"),
    PEARL("pearl"),
    CERAMIC("ceramic"),
    CHROME("chrome"),
    FACET("facet"),
    LIGHT("light");

    companion object {
        const val DEFAULT = "satin"
        fun fromKey(key: String?): AccentStyle = entries.firstOrNull { it.key == key } ?: SATIN
    }
}

val LocalAccentStyle = staticCompositionLocalOf { AccentStyle.SATIN }

/**
 * Android gradients require every stop to use the same color space. Compose lerp returns
 * the target's color space, so convert design highlights before blending with an HDR accent.
 */
private fun accentLerp(accent: Color, target: Color, fraction: Float): Color =
    lerp(accent, target.convert(accent.colorSpace), fraction)

/** Material shader palette derived from the main or separate playback accent. */
fun accentBrush(accent: Color, style: AccentStyle): Brush =
    accentMaterialBrush(accentMaterialStops(accent, style), style)

/** Shared stops let controls bound/lift their material without duplicating the style palette. */
internal fun accentMaterialStops(accent: Color, style: AccentStyle): List<Pair<Float, Color>> = when (style) {
    AccentStyle.LIGHT -> listOf(0f to accent)
    AccentStyle.SATIN -> listOf(
        0f to accentLerp(accent, Color.White, 0.40f),
        0.23f to accentLerp(accent, Color.Black, 0.12f),
        0.58f to accentLerp(accent, Color.White, 0.25f),
        1f to accentLerp(accent, Color.Black, 0.16f),
    )
    AccentStyle.GLASS -> listOf(
        0f to accentLerp(accent, Color.White, 0.48f),
        0.25f to accentLerp(accent, Color.White, 0.16f),
        0.60f to accent,
        1f to accentLerp(accent, Color.Black, 0.22f),
    )
    AccentStyle.GLOW -> listOf(
        0f to accentLerp(accent, Color.White, 0.18f),
        .5f to accent,
        1f to accentLerp(accent, Color.White, 0.30f),
    )
    AccentStyle.PEARL -> listOf(
        0f to accentLerp(accent, Color.White, 0.44f),
        0.32f to accentLerp(accent, Color(0xFFA9C6FF), 0.30f),
        0.69f to accentLerp(accent, Color(0xFFFFB9D7), 0.28f),
        1f to accentLerp(accent, Color.White, 0.26f),
    )
    // A broad, saturated body with a small light lip and shaded lower edge.
    AccentStyle.CERAMIC -> listOf(
        0f to accentLerp(accent, Color.White, 0.28f),
        0.10f to accentLerp(accent, Color.White, 0.06f),
        0.78f to accent,
        1f to accentLerp(accent, Color.Black, 0.14f),
    )
    // A narrow static reflection; the shaded sections retain the accent hue.
    AccentStyle.CHROME -> listOf(
        0f to accentLerp(accent, Color.White, 0.38f),
        0.35f to accentLerp(accent, Color.Black, 0.16f),
        0.47f to accentLerp(accent, Color.White, 0.70f),
        0.53f to accentLerp(accent, Color.White, 0.70f),
        0.59f to accentLerp(accent, Color.Black, 0.12f),
        1f to accentLerp(accent, Color.White, 0.24f),
    )
    // Three diagonal light planes, with short transitions to avoid a jagged seam.
    AccentStyle.FACET -> listOf(
        0f to accentLerp(accent, Color.White, 0.30f),
        0.33f to accentLerp(accent, Color.White, 0.30f),
        0.35f to accent,
        0.66f to accent,
        0.68f to accentLerp(accent, Color.Black, 0.13f),
        1f to accentLerp(accent, Color.Black, 0.13f),
    )
}

internal fun accentMaterialBrush(stops: List<Pair<Float, Color>>, style: AccentStyle): Brush = when (style) {
    AccentStyle.LIGHT -> SolidColor(stops.first().second)
    AccentStyle.GLOW -> Brush.horizontalGradient(*stops.toTypedArray())
    AccentStyle.CERAMIC -> Brush.verticalGradient(*stops.toTypedArray())
    else -> Brush.linearGradient(*stops.toTypedArray())
}

/**
 * Focus and selected borders are material accents, not the full glossy *surface* brush.
 * Each style has its own reflection geometry and tonal range, including on a thin
 * 2dp stroke. The former 68% lerp back to a single color flattened all the materials
 * into near-identical outlines. Never blend the whole border to white.
 *
 * A selected-only control uses the same material at a thinner stroke with a separate
 * persistent marker; focus adds stroke weight and the optional moving reflection.
 */
internal fun accentFocusBorderBrush(accent: Color, style: AccentStyle): Brush {
    if (style == AccentStyle.LIGHT) return SolidColor(accent)
    val lip = accentLerp(accent, Color.White, .20f)
    val highlight = accentLerp(accent, Color.White, .42f)
    val shadow = accentLerp(accent, Color.Black, .19f)
    val deepShadow = accentLerp(accent, Color.Black, .29f)
    val cool = accentLerp(accent, Color(0xFF8EBEFF), .30f)
    val warm = accentLerp(accent, Color(0xFFFFADCF), .30f)
    val stops = when (style) {
        AccentStyle.SATIN -> listOf(
            0f to lip, .24f to shadow, .55f to highlight, 1f to shadow,
        )
        AccentStyle.GLASS -> listOf(
            0f to highlight, .17f to lip, .45f to accent, 1f to shadow,
        )
        AccentStyle.GLOW -> listOf(0f to lip)
        AccentStyle.PEARL -> listOf(
            0f to lip, .32f to cool, .69f to warm, 1f to lip,
        )
        AccentStyle.CERAMIC -> listOf(
            0f to highlight, .12f to highlight, .14f to accent,
            .84f to accent, 1f to shadow,
        )
        AccentStyle.CHROME -> listOf(
            0f to shadow, .34f to deepShadow, .46f to highlight,
            .54f to highlight, .64f to deepShadow, 1f to lip,
        )
        AccentStyle.FACET -> listOf(
            0f to lip, .33f to lip, .35f to accent,
            .66f to accent, .68f to shadow, 1f to shadow,
        )
        AccentStyle.LIGHT -> error("Light is a flat-color border")
    }
    return when (style) {
        AccentStyle.GLOW -> SolidColor(lip)
        // A vertical upper lip is what distinguishes Glass from the diagonal Satin.
        AccentStyle.GLASS -> Brush.verticalGradient(*stops.toTypedArray())
        else -> accentMaterialBrush(stops, style)
    }
}

/** Keep unfocused content neutral; only active elements receive a material wash. */
fun accentWashOpacity(style: AccentStyle): Float = when (style) {
    AccentStyle.LIGHT -> 0f
    AccentStyle.SATIN -> 0.16f
    AccentStyle.GLASS -> 0.12f
    AccentStyle.GLOW -> 0.11f
    AccentStyle.PEARL -> 0.14f
    AccentStyle.CERAMIC -> 0.12f
    AccentStyle.CHROME -> 0.14f
    AccentStyle.FACET -> 0.14f
}
