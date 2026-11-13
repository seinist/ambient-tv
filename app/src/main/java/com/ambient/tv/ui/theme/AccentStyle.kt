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
    LIGHT("light");

    companion object {
        const val DEFAULT = "satin"
        fun fromKey(key: String?): AccentStyle = entries.firstOrNull { it.key == key } ?: SATIN
    }
}

val LocalAccentStyle = staticCompositionLocalOf { AccentStyle.SATIN }

/** Material shader palette derived from the main or separate playback accent. */
fun accentBrush(accent: Color, style: AccentStyle): Brush = when (style) {
    AccentStyle.LIGHT -> SolidColor(accent)
    AccentStyle.SATIN -> Brush.linearGradient(
        0f to lerp(accent, Color.White, 0.40f),
        0.23f to lerp(accent, Color.Black, 0.12f),
        0.58f to lerp(accent, Color.White, 0.25f),
        1f to lerp(accent, Color.Black, 0.16f),
    )
    AccentStyle.GLASS -> Brush.linearGradient(
        0f to lerp(accent, Color.White, 0.48f),
        0.25f to lerp(accent, Color.White, 0.16f),
        0.60f to accent,
        1f to lerp(accent, Color.Black, 0.22f),
    )
    AccentStyle.GLOW -> Brush.horizontalGradient(
        listOf(lerp(accent, Color.White, 0.18f), accent, lerp(accent, Color.White, 0.30f)),
    )
    AccentStyle.PEARL -> Brush.linearGradient(
        0f to lerp(accent, Color.White, 0.44f),
        0.32f to lerp(accent, Color(0xFFA9C6FF), 0.30f),
        0.69f to lerp(accent, Color(0xFFFFB9D7), 0.28f),
        1f to lerp(accent, Color.White, 0.26f),
    )
}

/** Keep unfocused content neutral; only active elements receive a material wash. */
fun accentWashOpacity(style: AccentStyle): Float = when (style) {
    AccentStyle.LIGHT -> 0f
    AccentStyle.SATIN -> 0.16f
    AccentStyle.GLASS -> 0.12f
    AccentStyle.GLOW -> 0.11f
    AccentStyle.PEARL -> 0.14f
}
