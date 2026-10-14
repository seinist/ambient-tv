package com.ambient.tv.ui.theme

import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFE5A00D)
val GoldMuted = Color(0xFFC88A12)
val AppBackground = Color(0xFF0A0C0F)
val AppSurface = Color(0xFF14181D)
val AppSurfaceRaised = Color(0xFF20262D)
val AppOnSurface = Color(0xFFF3F4F5)
val AppOnSurfaceMuted = Color(0xFFB4BBC3)
val AppOutline = Color(0xFF343A40)

val AccentOptions: LinkedHashMap<String, Color> = linkedMapOf(
    "gold" to Gold,
    "orange" to Color(0xFFFF8A33),
    "blue" to Color(0xFF5DA9FF),
    "teal" to Color(0xFF38C7B5),
    "green" to Color(0xFF63C174),
    "purple" to Color(0xFFA77BFF),
    "pink" to Color(0xFFFF77A8),
    "red" to Color(0xFFFF665F),
)

fun accentColorFor(key: String, customHex: String = "#E5A00D"): Color {
    if (key == "custom") {
        return Color(0xFF000000 or normalizedCustomAccentHex(customHex).drop(1).toLong(16))
    }
    // "artwork" intentionally falls back to Gold outside media-detail contexts; detail/cinematic
    // surfaces apply a content-derived tint locally without making the whole application flash colors.
    return AccentOptions[key] ?: Gold
}

fun normalizedAccentKey(key: String): String = key.takeIf {
    it in AccentOptions || it == "artwork" || it == "custom"
} ?: "gold"
