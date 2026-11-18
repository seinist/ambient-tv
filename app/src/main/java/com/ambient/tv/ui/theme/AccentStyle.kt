package com.ambient.tv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp

/**
 * Accent material is independent of the chosen accent hue and playback accent.
 * Each style has its own material and its own subtle motion (see AccentMotionPaints):
 * Light (flat), Satin, Glass, Chrome (metal), Glow, Pearl, Ceramic and Facet. Light keeps the
 * solid-color look. Static base brushes; optional, Maximum-only motion is drawn separately.
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

/**
 * Rotate the accent's hue in sRGB, keeping its lightness and saturation. Pearl derives its
 * iridescence from the chosen accent instead of fixed blue/pink tints, so green or orange
 * accents shimmer in their own neighbouring hues rather than turning muddy.
 */
internal fun accentHueShift(accent: Color, degrees: Float): Color {
    val srgb = accent.convert(androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb)
    val r = srgb.red.coerceIn(0f, 1f); val g = srgb.green.coerceIn(0f, 1f); val b = srgb.blue.coerceIn(0f, 1f)
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
    val light = (max + min) / 2f
    if (delta <= 0.0001f) return accent
    val sat = if (light > .5f) delta / (2f - max - min) else delta / (max + min)
    var hue = when (max) {
        r -> ((g - b) / delta + (if (g < b) 6f else 0f))
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f
    hue = ((hue + degrees) % 360f + 360f) % 360f
    return Color.hsl(hue, sat.coerceIn(0f, 1f), light.coerceIn(0f, 1f), srgb.alpha).convert(accent.colorSpace)
}

/** Facet planes keep one fixed size (px at the TV's UI density), whatever the control's shape. */
private const val FACET_PLANE_PX = 18f
private const val FACET_BORDER_PLANE_PX = 10f

/** Repeating diagonal planes with short blends, so thin strokes never show jagged seams. */
private fun facetBrush(light: Color, mid: Color, shade: Color, planePx: Float): Brush {
    val blend = .04f
    val third = 1f / 3f
    return Brush.linearGradient(
        0f to light, third - blend to light, third + blend to mid,
        2 * third - blend to mid, 2 * third + blend to shade, 1f - blend to shade, 1f to light,
        start = androidx.compose.ui.geometry.Offset.Zero,
        // 60° planes: the gradient runs perpendicular to them across three plane widths.
        end = androidx.compose.ui.geometry.Offset(planePx * 3f * .5f, planePx * 3f * .866f),
        tileMode = androidx.compose.ui.graphics.TileMode.Repeated,
    )
}

/** Material shader palette derived from the main or separate playback accent. */
fun accentBrush(accent: Color, style: AccentStyle): Brush =
    accentMaterialBrush(accentMaterialStops(accent, style), style)

/**
 * Shared stops let controls bound/lift their material without duplicating the style palette.
 * Every style is lit from above (top to bottom), so a wide chip, a square tile and a long
 * sidebar row show the same material instead of a diagonal that depends on their aspect ratio.
 */
internal fun accentMaterialStops(accent: Color, style: AccentStyle): List<Pair<Float, Color>> = when (style) {
    AccentStyle.LIGHT -> listOf(0f to accent)
    // Soft, even sheen from the top.
    AccentStyle.SATIN -> listOf(
        0f to accentLerp(accent, Color.White, 0.30f),
        0.45f to accent,
        1f to accentLerp(accent, Color.Black, 0.24f),
    )
    // A bright glass lip along the upper edge above a clear body.
    AccentStyle.GLASS -> listOf(
        0f to accentLerp(accent, Color.White, 0.55f),
        0.16f to accentLerp(accent, Color.White, 0.50f),
        0.24f to accent,
        1f to accentLerp(accent, Color.Black, 0.24f),
    )
    AccentStyle.GLOW -> listOf(
        0f to accentLerp(accent, Color.White, 0.22f),
        .5f to accent,
        1f to accentLerp(accent, Color.White, 0.12f),
    )
    // Iridescence from the accent's own neighbouring hues.
    AccentStyle.PEARL -> listOf(
        0f to accentLerp(accentHueShift(accent, -28f), Color.White, 0.25f),
        0.5f to accent,
        1f to accentHueShift(accent, 28f),
    )
    // A saturated, matte glaze: little contrast, deep and calm.
    AccentStyle.CERAMIC -> listOf(
        0f to accentLerp(accent, Color.White, 0.12f),
        0.5f to accent,
        1f to accentLerp(accent, Color.Black, 0.12f),
    )
    // Brushed metal: a hard horizontal specular line in the upper half.
    AccentStyle.CHROME -> listOf(
        0f to accentLerp(accent, Color.White, 0.30f),
        0.30f to accentLerp(accent, Color.Black, 0.24f),
        0.46f to accentLerp(accent, Color.White, 0.55f),
        0.50f to accentLerp(accent, Color.White, 0.55f),
        0.62f to accentLerp(accent, Color.Black, 0.24f),
        1f to accentLerp(accent, Color.White, 0.30f),
    )
    // Light, mid and shade planes; the brush tiles them at a fixed size.
    AccentStyle.FACET -> listOf(
        0f to accentLerp(accent, Color.White, 0.30f),
        0.5f to accent,
        1f to accentLerp(accent, Color.Black, 0.24f),
    )
}

internal fun accentMaterialBrush(stops: List<Pair<Float, Color>>, style: AccentStyle): Brush = when (style) {
    AccentStyle.LIGHT -> SolidColor(stops.first().second)
    AccentStyle.FACET -> facetBrush(stops.first().second, stops[stops.size / 2].second, stops.last().second, FACET_PLANE_PX)
    else -> Brush.verticalGradient(*stops.toTypedArray())
}

/**
 * Focus and selected borders are material accents, not the full glossy *surface* brush.
 * All of them are lit from above, so even a 2dp stroke reads as material: a lighter top
 * edge, the accent along the sides and a shaded bottom edge. Never blend the whole border
 * to white. A selected-only control uses the same material at a thinner stroke; focus adds
 * stroke weight and the optional per-style motion.
 */
internal fun accentFocusBorderBrush(accent: Color, style: AccentStyle): Brush {
    if (style == AccentStyle.LIGHT) return SolidColor(accent)
    val lip = accentLerp(accent, Color.White, .30f)
    val highlight = accentLerp(accent, Color.White, .55f)
    val shadow = accentLerp(accent, Color.Black, .24f)
    val stops = when (style) {
        AccentStyle.SATIN -> listOf(0f to lip, .45f to accent, 1f to shadow)
        AccentStyle.GLASS -> listOf(0f to highlight, .16f to highlight, .24f to accent, 1f to shadow)
        AccentStyle.GLOW -> listOf(0f to lip)
        AccentStyle.PEARL -> listOf(
            0f to accentLerp(accentHueShift(accent, -28f), Color.White, .25f),
            .5f to accent, 1f to accentHueShift(accent, 28f),
        )
        AccentStyle.CERAMIC -> listOf(
            0f to accentLerp(accent, Color.White, .12f), .5f to accent, 1f to accentLerp(accent, Color.Black, .12f),
        )
        AccentStyle.CHROME -> listOf(
            0f to lip, .30f to shadow, .46f to highlight, .50f to highlight, .62f to shadow, 1f to lip,
        )
        AccentStyle.FACET -> return facetBrush(lip, accent, shadow, FACET_BORDER_PLANE_PX)
        AccentStyle.LIGHT -> error("Light is a flat-color border")
    }
    return if (style == AccentStyle.GLOW) SolidColor(lip) else Brush.verticalGradient(*stops.toTypedArray())
}

/** Keep unfocused content neutral; only active elements receive a material wash. */
/**
 * Somewhat more material on the surface than before. Ceramic's matte glaze carries the most
 * body, Glow the least (its halo speaks instead). Controls halve this and stay within the
 * text-contrast budget together with the motion peak (see ControlColors).
 */
fun accentWashOpacity(style: AccentStyle): Float = when (style) {
    AccentStyle.LIGHT -> 0f
    AccentStyle.SATIN -> 0.20f
    AccentStyle.GLASS -> 0.18f
    AccentStyle.GLOW -> 0.14f
    AccentStyle.PEARL -> 0.20f
    AccentStyle.CERAMIC -> 0.22f
    AccentStyle.CHROME -> 0.18f
    AccentStyle.FACET -> 0.18f
}
