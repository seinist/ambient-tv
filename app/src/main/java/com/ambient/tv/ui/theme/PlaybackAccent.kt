package com.ambient.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/** Invert the selected sRGB channels before accessibility adjustments or HDR lifting. */
fun rgbComplement(accent: Color): Color {
    val rgb = accent.convert(ColorSpaces.Srgb)
    return Color(1f - rgb.red, 1f - rgb.green, 1f - rgb.blue, 1f)
}

@Immutable
data class PlaybackAccent(val indicator: Color, val text: Color)

/** A semantic role: never use this for focus, selected options or ambient artwork. */
val LocalPlaybackAccent = staticCompositionLocalOf {
    val complement = playbackAccentColorFor("gold")
    PlaybackAccent(complement, accessibleAccent(complement, AppSurface))
}

/** Fixed pairs are part of the theme palette, independent of text contrast or HDR transforms. */
val PlaybackAccentOptions: Map<String, Color> = linkedMapOf(
    "gold" to Color(0xFF1A5FF2),
    "orange" to Color(0xFF0075CC),
    "blue" to Color(0xFFA25600),
    "teal" to Color(0xFFC7384A),
    "green" to Color(0xFF9C3E8B),
    "purple" to Color(0xFF588400),
    "pink" to Color(0xFF008857),
    "red" to Color(0xFF0099A0),
)

fun playbackAccentColorFor(
    key: String,
    customPlaybackHex: String? = null,
    customPrimaryHex: String = "#E5A00D",
): Color = if (key == "custom") {
    Color(0xFF000000 or normalizedCustomPlaybackAccentHex(customPlaybackHex, customPrimaryHex).drop(1).toLong(16))
} else {
    // Artwork colors remain local decoration; functional playback has a stable gold/blue pair.
    PlaybackAccentOptions[key] ?: PlaybackAccentOptions.getValue("gold")
}
