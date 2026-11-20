package com.ambient.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.ambient.tv.ui.display.UI_HDR_HEADROOM_CAP
import com.ambient.tv.ui.display.uiHdrHighlight

internal val LocalControlPalette = staticCompositionLocalOf<ControlPalette?> { null }

internal const val CONTROL_ACCENT_MOTION_EMPHASIS = .33f

/** Conservative sum of the drawing passes, even where their reflections do not overlap. */
internal fun controlAccentMotionMaxOpacity(style: AccentStyle): Float =
    CONTROL_ACCENT_MOTION_EMPHASIS * accentMotionPeakAlpha(style, stroke = false)

@Immutable
internal data class ControlPaletteInput(
    val surface: Color,
    val raisedSurface: Color,
    val content: Color,
    val mutedContent: Color,
    val accent: Color,
    val style: AccentStyle,
    val headroom: Float,
    val materialAccent: Color = accent,
)

/**
 * Controls keep light content on an opaque dark surface, including above video/artwork.
 * Resolve the complete palette once per theme. Animated colors never need a contrast
 * calculation, shader inspection or a read of the video underneath the control.
 */
@Immutable
internal class ControlPalette(
    val input: ControlPaletteInput,
    private val containers: List<Color>,
    private val disabledContainer: Color,
    val content: Color,
    private val disabledContent: Color,
    private val focusOutline: Color,
    private val accentFocusOutline: Color,
    private val selectedOutline: Color,
    private val prominentOutline: Color,
    val materialStops: List<Pair<Float, Color>>,
    val style: AccentStyle,
    private val materialOpacity: Float,
) {
    fun colors(
        focused: Boolean,
        selected: Boolean = false,
        enabled: Boolean = true,
        prominent: Boolean = false,
        pressed: Boolean = false,
        focusedAccent: Boolean = false,
    ): ControlColors {
        // Some TV actions deliberately retain focus while becoming disabled (e.g. a
        // request in flight). Keep that location visible without an active accent wash.
        if (!enabled) return ControlColors(
            disabledContainer, disabledContent,
            if (focused) focusOutline.copy(alpha = .65f) else Color.Transparent,
            0f,
        )
        val index = (if (selected) 1 else 0) or (if (prominent) 2 else 0) or
            (if (focused) 4 else 0) or (if (pressed) 8 else 0)
        return ControlColors(
            container = containers[index],
            content = content,
            outline = when {
                focused -> if (focusedAccent) accentFocusOutline else focusOutline
                selected -> selectedOutline
                prominent -> prominentOutline
                else -> Color.Transparent
            },
            washOpacity = if (focused || selected || prominent) materialOpacity else 0f,
        )
    }

    fun materialBrush(): Brush = accentMaterialBrush(materialStops, style)

    /**
     * The high-contrast semantic outline is intentionally light, but MUST NOT feed the
     * material shader: it had already been lifted toward white for accessibility,
     * washing out Gold, Red, Blue etc. until every style looked alike.
     * Theme.primary is contrast-adjusted against the dark base, so use its actual hue
     * for the visible stroke; keep the semantic alpha for selected/focused strength.
     */
    fun focusBorderBrush(): Brush = accentFocusBorderBrush(input.materialAccent.copy(alpha = 1f), style)

    /** The same material, thinner and static until focused. */
    fun selectedBorderBrush(): Brush = accentFocusBorderBrush(input.materialAccent.copy(alpha = 1f), style)
}

@Immutable
internal data class ControlColors(
    val container: Color,
    val content: Color,
    val outline: Color,
    val washOpacity: Float,
)

/** Theme colors have already been lifted; contrast decisions use their SDR design values. */
internal fun controlDesignColor(color: Color, headroom: Float): Color {
    if (color.colorSpace != ColorSpaces.LinearExtendedSrgb || !headroom.isFinite() || headroom <= 1f) {
        return color.convert(ColorSpaces.Srgb)
    }
    val gain = minOf(headroom, UI_HDR_HEADROOM_CAP)
    return Color(
        (color.red / gain).coerceIn(0f, 1f),
        (color.green / gain).coerceIn(0f, 1f),
        (color.blue / gain).coerceIn(0f, 1f),
        color.alpha,
        ColorSpaces.LinearExtendedSrgb,
    ).convert(ColorSpaces.Srgb)
}

internal fun controlPalette(
    surface: Color,
    raisedSurface: Color,
    content: Color,
    mutedContent: Color,
    accent: Color,
    style: AccentStyle,
    headroom: Float = 1f,
    materialAccent: Color = accent,
): ControlPalette {
    val base = controlDesignColor(surface, headroom).copy(alpha = 1f)
    val raised = controlDesignColor(raisedSurface, headroom).copy(alpha = 1f)
    val foreground = controlDesignColor(content, headroom).copy(alpha = 1f)
    val accentColor = controlDesignColor(accent, headroom).copy(alpha = 1f)
    val materialAccentColor = controlDesignColor(materialAccent, headroom).copy(alpha = 1f)
    // Reserve the brightest possible material stop plus its optional animated reflection,
    // in linear light (the more demanding HDR blend). Keeping that reservation even while
    // motion is off avoids a brightness jump when focus starts/stops the clock. The 5:1
    // target leaves headroom for sRGB quantization and Oklab color-animation intermediates.
    val washOpacity = accentWashOpacity(style) * .5f
    val maximumOverlayOpacity = 1f - (1f - washOpacity) * (1f - controlAccentMotionMaxOpacity(style))
    val maxDrawnLuminance = ((foreground.luminance() + .05f) / 5f - .05f).coerceAtLeast(0f)
    val maxFillLuminance = minOf(
        .06f,
        ((maxDrawnLuminance - maximumOverlayOpacity) / (1f - maximumOverlayOpacity))
            .coerceAtLeast(0f) * .95f,
    )
    val idle = lerp(base, raised, .35f)
    val containers = List(16) { index ->
        val selected = index and 1 != 0
        val prominent = index and 2 != 0
        val focused = index and 4 != 0
        val pressed = index and 8 != 0
        val tint = when {
            prominent && selected -> .32f
            prominent -> .25f
            selected -> .17f
            else -> 0f
        }
        // Preserve the real accent hue in selected surfaces, not the white-lifted UI tint.
        var fill = lerp(idle, materialAccentColor, tint)
        // Focus adds a neutral lift without replacing the persistent selection tint.
        if (focused) fill = lerp(fill, foreground, .045f)
        fill = limitControlLuminance(fill, maxFillLuminance)
        if (pressed) fill = scaleControlLuminance(fill, .78f)
        uiHdrHighlight(fill, headroom)
    }
    // Selection needs a visible accent edge even on chips without a checkmark. Resolve
    // that edge against the brightest possible styled fill, with enough contrast left
    // after its opacity is applied. Focus remains a separate neutral outline.
    val edge = accessibleAccent(accentColor, raised)
    val brightestFill = Color(
        maxDrawnLuminance, maxDrawnLuminance, maxDrawnLuminance,
        colorSpace = ColorSpaces.LinearExtendedSrgb,
    ).convert(ColorSpaces.Srgb)
    val accentFocus = accessibleAccent(accentColor, brightestFill)
    return ControlPalette(
        input = ControlPaletteInput(surface, raisedSurface, content, mutedContent, accent, style, headroom, materialAccent),
        containers = containers,
        disabledContainer = uiHdrHighlight(scaleControlLuminance(idle, .70f), headroom),
        content = content.copy(alpha = 1f),
        disabledContent = mutedContent.copy(alpha = .55f),
        focusOutline = uiHdrHighlight(accentFocus, headroom).copy(alpha = .94f),
        accentFocusOutline = uiHdrHighlight(accentFocus, headroom),
        selectedOutline = uiHdrHighlight(accentFocus, headroom).copy(alpha = .85f),
        prominentOutline = uiHdrHighlight(edge, headroom).copy(alpha = .48f),
        materialStops = accentMaterialStops(materialAccentColor, style).map { (position, color) ->
            val lifted = uiHdrHighlight(color, headroom)
            // The sRGB-to-linear matrix can leave a tiny negative component for an
            // exact zero channel (e.g. cyan). Keep the bounded material nonnegative.
            position to if (lifted.colorSpace == ColorSpaces.LinearExtendedSrgb) Color(
                lifted.red.coerceAtLeast(0f),
                lifted.green.coerceAtLeast(0f),
                lifted.blue.coerceAtLeast(0f),
                lifted.alpha,
                ColorSpaces.LinearExtendedSrgb,
            ) else lifted
        },
        style = style,
        materialOpacity = washOpacity,
    )
}

private fun limitControlLuminance(color: Color, maximum: Float): Color {
    val luminance = color.luminance()
    return if (luminance <= maximum) color.copy(alpha = 1f)
    else scaleControlLuminance(color, maximum / luminance)
}

private fun scaleControlLuminance(color: Color, scale: Float): Color {
    val linear = color.convert(ColorSpaces.LinearExtendedSrgb)
    return Color(
        (linear.red * scale).coerceIn(0f, 1f),
        (linear.green * scale).coerceIn(0f, 1f),
        (linear.blue * scale).coerceIn(0f, 1f),
        1f,
        ColorSpaces.LinearExtendedSrgb,
    ).convert(ColorSpaces.Srgb)
}
