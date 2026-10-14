package com.ambient.tv.ui.theme

/** Persist only opaque RGB. Partial text stays in the settings draft, never in the theme/backup. */
fun normalizedCustomAccentHex(value: String, fallbackHex: String = "#E5A00D"): String {
    val hex = value.trim().removePrefix("#")
    if (hex.length !in setOf(6, 8) || hex.any { it.digitToIntOrNull(16) == null }) return fallbackHex
    return "#" + hex.takeLast(6).uppercase(java.util.Locale.ROOT)
}

/** Old custom themes gain their previous RGB complement; an explicit second color is independent. */
fun normalizedCustomPlaybackAccentHex(value: String?, primaryHex: String = "#E5A00D"): String =
    normalizedCustomAccentHex(value.orEmpty(), rgbComplementHex(primaryHex))

fun rgbComplementHex(value: String): String = "#%06X".format(
    java.util.Locale.ROOT,
    0xFFFFFF xor normalizedCustomAccentHex(value).drop(1).toInt(16),
)
