package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/** The clock in page headers, on the app home, in the player OSD; the screensaver shares format and date. */
@Serializable
data class ClockSettings(
    val display: String = DISPLAY_PLAYER,
    val format: String = FORMAT_24,
    val seconds: Boolean = false,
    val date: String = DATE_SHORT,
    val dateBeside: Boolean = false,
    val size: String = SIZE_MEDIUM,
    val font: String = FONT_UI,
    val color: String = COLOR_WHITE,
    val opacityPercent: Int = 92,
    val shadow: String = SHADOW_SOFT,
    val endTime: Boolean = true,
) {
    val inHeader: Boolean get() = display == DISPLAY_EVERYWHERE || display == DISPLAY_PLAYER
    val onHome: Boolean get() = display != DISPLAY_OFF
    val inPlayer: Boolean get() = display == DISPLAY_PLAYER

    fun use24Hour(systemIs24Hour: Boolean): Boolean = when (format) {
        FORMAT_12 -> false
        FORMAT_SYSTEM -> systemIs24Hour
        else -> true
    }

    /** Unknown keys from a newer or damaged backup fall back to the defaults. */
    fun normalized(): ClockSettings = copy(
        display = display.takeIf { it in displays } ?: DISPLAY_PLAYER,
        format = format.takeIf { it in formats } ?: FORMAT_24,
        date = date.takeIf { it in dates } ?: DATE_SHORT,
        size = size.takeIf { it in sizes } ?: SIZE_MEDIUM,
        font = font.takeIf { it in fonts } ?: FONT_UI,
        color = color.takeIf { it in colors } ?: COLOR_WHITE,
        opacityPercent = opacityPercent.coerceIn(40, 100),
        shadow = shadow.takeIf { it in shadows } ?: SHADOW_SOFT,
    )

    companion object {
        const val DISPLAY_OFF = "off"
        const val DISPLAY_HOME = "home"
        const val DISPLAY_EVERYWHERE = "everywhere"
        const val DISPLAY_PLAYER = "player"
        val displays = listOf(DISPLAY_OFF, DISPLAY_HOME, DISPLAY_EVERYWHERE, DISPLAY_PLAYER)

        const val FORMAT_24 = "24"
        const val FORMAT_12 = "12"
        const val FORMAT_SYSTEM = "system"
        val formats = listOf(FORMAT_24, FORMAT_12, FORMAT_SYSTEM)

        const val DATE_OFF = "off"
        const val DATE_SHORT = "short"
        const val DATE_LONG = "long"
        const val DATE_NUMERIC = "numeric"
        val dates = listOf(DATE_OFF, DATE_SHORT, DATE_LONG, DATE_NUMERIC)

        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"
        val sizes = listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE)

        const val FONT_UI = "ui"
        const val FONT_LIGHT = "light"
        const val FONT_BOLD = "bold"
        const val FONT_MONO = "mono"
        val fonts = listOf(FONT_UI, FONT_LIGHT, FONT_BOLD, FONT_MONO)

        const val COLOR_WHITE = "white"
        const val COLOR_ACCENT = "accent"
        const val COLOR_DIM = "dim"
        val colors = listOf(COLOR_WHITE, COLOR_ACCENT, COLOR_DIM)

        const val SHADOW_OFF = "off"
        const val SHADOW_SOFT = "soft"
        const val SHADOW_STRONG = "strong"
        val shadows = listOf(SHADOW_OFF, SHADOW_SOFT, SHADOW_STRONG)
    }
}
