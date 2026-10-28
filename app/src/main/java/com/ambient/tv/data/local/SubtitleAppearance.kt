package com.ambient.tv.data.local

import kotlinx.serialization.Serializable

/**
 * How text subtitles (SRT, WebVTT, text tracks) look in every player. ASS/SSA keep their own
 * styling; size and position still scale them where the engine allows.
 */
@Serializable
data class SubtitleAppearance(
    val size: String = SIZE_MEDIUM,
    val color: String = COLOR_WHITE,
    val edge: String = EDGE_OUTLINE,
    val background: String = BACKGROUND_NONE,
    val bold: Boolean = true,
    /** Distance of the bottom line from the picture's lower edge, in percent of its height. */
    val positionPercent: Int = 6,
) {
    /** Unknown keys from a newer or damaged backup fall back to the defaults. */
    fun normalized(): SubtitleAppearance = copy(
        size = size.takeIf { it in sizes } ?: SIZE_MEDIUM,
        color = color.takeIf { it in colors } ?: COLOR_WHITE,
        edge = edge.takeIf { it in edges } ?: EDGE_OUTLINE,
        background = background.takeIf { it in backgrounds } ?: BACKGROUND_NONE,
        positionPercent = positionPercent.coerceIn(MIN_POSITION, MAX_POSITION),
    )

    /** Text height as a fraction of the picture height (Media3's fractional text size). */
    val textFraction: Float get() = when (size) {
        SIZE_SMALL -> 0.036f
        SIZE_LARGE -> 0.055f
        SIZE_XLARGE -> 0.066f
        else -> 0.045f
    }

    /** Text color as ARGB. */
    val textArgb: Int get() = when (color) {
        COLOR_YELLOW -> 0xFFFFE14D.toInt()
        COLOR_GRAY -> 0xFFD0D0D0.toInt()
        COLOR_CYAN -> 0xFF7FE7FF.toInt()
        COLOR_GREEN -> 0xFF9CF59C.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    /** Box behind the text as ARGB; fully transparent without a box. */
    val backgroundArgb: Int get() = when (background) {
        BACKGROUND_TRANSLUCENT -> 0x99000000.toInt()
        BACKGROUND_OPAQUE -> 0xFF000000.toInt()
        else -> 0x00000000
    }

    companion object {
        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"
        const val SIZE_XLARGE = "xlarge"
        val sizes = listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE, SIZE_XLARGE)

        const val COLOR_WHITE = "white"
        const val COLOR_YELLOW = "yellow"
        const val COLOR_GRAY = "gray"
        const val COLOR_CYAN = "cyan"
        const val COLOR_GREEN = "green"
        val colors = listOf(COLOR_WHITE, COLOR_YELLOW, COLOR_GRAY, COLOR_CYAN, COLOR_GREEN)

        const val EDGE_NONE = "none"
        const val EDGE_OUTLINE = "outline"
        const val EDGE_SHADOW = "shadow"
        val edges = listOf(EDGE_OUTLINE, EDGE_SHADOW, EDGE_NONE)

        const val BACKGROUND_NONE = "none"
        const val BACKGROUND_TRANSLUCENT = "translucent"
        const val BACKGROUND_OPAQUE = "opaque"
        val backgrounds = listOf(BACKGROUND_NONE, BACKGROUND_TRANSLUCENT, BACKGROUND_OPAQUE)

        const val MIN_POSITION = 2
        const val MAX_POSITION = 30
    }
}

/** `#AARRGGBB` as MPV reads colors. */
private fun mpvColor(argb: Int): String = "#%08X".format(argb)

/**
 * MPV properties for text subtitles. Sizes and margins are in MPV's scaled pixels, where the
 * picture is 720 high; the box needs sub-border-style=background-box (MPV 0.38+).
 */
fun SubtitleAppearance.mpvProperties(): List<Pair<String, String>> {
    val a = normalized()
    val box = a.background != SubtitleAppearance.BACKGROUND_NONE
    return listOf(
        "sub-font-size" to (a.textFraction * 720f * 1.25f).toInt().toString(),
        "sub-color" to mpvColor(a.textArgb),
        "sub-bold" to if (a.bold) "yes" else "no",
        "sub-border-style" to if (box) "background-box" else "outline-and-shadow",
        "sub-back-color" to mpvColor(a.backgroundArgb),
        "sub-outline-color" to "#FF000000",
        "sub-outline-size" to if (!box && a.edge == SubtitleAppearance.EDGE_OUTLINE) "2.5" else "0",
        "sub-shadow-offset" to if (!box && a.edge == SubtitleAppearance.EDGE_SHADOW) "2.5" else "0",
        "sub-margin-y" to (a.positionPercent * 720 / 100).toString(),
        // ASS/SSA keep their own fonts, colours and positions.
        "sub-ass-override" to "no",
    )
}

/**
 * LibVLC instance options for the freetype text renderer and the subtitle margin (VLC reads
 * them when the instance is created). The margin is in source pixels; 1080 lines are assumed.
 */
fun SubtitleAppearance.vlcOptions(): List<String> {
    val a = normalized()
    val box = a.background != SubtitleAppearance.BACKGROUND_NONE
    return listOfNotNull(
        "--sub-text-scale=${(a.textFraction / 0.045f * 100f).toInt()}",
        "--freetype-color=${a.textArgb and 0xFFFFFF}",
        if (a.bold) "--freetype-bold" else "--no-freetype-bold",
        "--freetype-outline-thickness=${if (!box && a.edge == SubtitleAppearance.EDGE_OUTLINE) 4 else 0}",
        "--freetype-shadow-opacity=${if (!box && a.edge == SubtitleAppearance.EDGE_SHADOW) 160 else 0}",
        "--freetype-background-color=0",
        "--freetype-background-opacity=${(a.backgroundArgb ushr 24) and 0xFF}",
        "--sub-margin=${a.positionPercent * 1080 / 100}",
    )
}
