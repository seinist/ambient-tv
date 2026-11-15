package com.ambient.tv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp

/**
 * Accent material is independent of the chosen accent hue and playback accent.
 * Light intentionally preserves the previous solid-color look. All other styles
 * use static brushes; they need no blur or continuously updated animation.
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
fun accentBrush(accent: Color, style: AccentStyle): Brush = when (style) {
    AccentStyle.LIGHT -> SolidColor(accent)
    AccentStyle.SATIN -> Brush.linearGradient(
        0f to accentLerp(accent, Color.White, 0.40f),
        0.23f to accentLerp(accent, Color.Black, 0.12f),
        0.58f to accentLerp(accent, Color.White, 0.25f),
        1f to accentLerp(accent, Color.Black, 0.16f),
    )
    AccentStyle.GLASS -> Brush.linearGradient(
        0f to accentLerp(accent, Color.White, 0.48f),
        0.25f to accentLerp(accent, Color.White, 0.16f),
        0.60f to accent,
        1f to accentLerp(accent, Color.Black, 0.22f),
    )
    AccentStyle.GLOW -> Brush.horizontalGradient(
        listOf(accentLerp(accent, Color.White, 0.18f), accent, accentLerp(accent, Color.White, 0.30f)),
    )
    AccentStyle.PEARL -> Brush.linearGradient(
        0f to accentLerp(accent, Color.White, 0.44f),
        0.32f to accentLerp(accent, Color(0xFFA9C6FF), 0.30f),
        0.69f to accentLerp(accent, Color(0xFFFFB9D7), 0.28f),
        1f to accentLerp(accent, Color.White, 0.26f),
    )
    // A broad, saturated body with a small light lip and shaded lower edge.
    AccentStyle.CERAMIC -> Brush.verticalGradient(
        0f to accentLerp(accent, Color.White, 0.28f),
        0.10f to accentLerp(accent, Color.White, 0.06f),
        0.78f to accent,
        1f to accentLerp(accent, Color.Black, 0.14f),
    )
    // A narrow static reflection; the shaded sections retain the accent hue.
    AccentStyle.CHROME -> Brush.linearGradient(
        0f to accentLerp(accent, Color.White, 0.38f),
        0.35f to accentLerp(accent, Color.Black, 0.16f),
        0.47f to accentLerp(accent, Color.White, 0.70f),
        0.53f to accentLerp(accent, Color.White, 0.70f),
        0.59f to accentLerp(accent, Color.Black, 0.12f),
        1f to accentLerp(accent, Color.White, 0.24f),
    )
    // Three diagonal light planes, with short transitions to avoid a jagged seam.
    AccentStyle.FACET -> Brush.linearGradient(
        0f to accentLerp(accent, Color.White, 0.30f),
        0.33f to accentLerp(accent, Color.White, 0.30f),
        0.35f to accent,
        0.66f to accent,
        0.68f to accentLerp(accent, Color.Black, 0.13f),
        1f to accentLerp(accent, Color.Black, 0.13f),
    )
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
