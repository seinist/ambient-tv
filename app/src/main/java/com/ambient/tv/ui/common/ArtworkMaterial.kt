package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.theme.AccentStyle
import com.ambient.tv.ui.theme.LocalAccentStyle
import com.ambient.tv.ui.theme.accentBrush
import com.ambient.tv.ui.theme.accentWashOpacity

/** Keep artwork washes within SDR white without clipping away an HDR accent's hue. */
internal fun artworkWashAccent(accent: Color): Color {
    val linear = accent.convert(ColorSpaces.LinearExtendedSrgb)
    val peak = maxOf(linear.red, linear.green, linear.blue)
    if (peak <= 1f) return accent
    return Color(
        linear.red / peak,
        linear.green / peak,
        linear.blue / peak,
        accent.alpha,
        ColorSpaces.LinearExtendedSrgb,
    ).convert(ColorSpaces.Srgb)
}

/**
 * Material for small artwork badges and metadata strips. The dark base stays underneath the
 * subtle wash so bright posters cannot wash out the solid text/icons. Cached outlines and a
 * static brush keep dense rows free of per-badge animations, blur and offscreen layers.
 */
@Composable
internal fun Modifier.artworkAccentSurface(
    shape: Shape,
    accent: Color,
    baseColor: Color = ArtworkChipFill,
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 1.dp,
    emphasized: Boolean = false,
): Modifier {
    val style = LocalAccentStyle.current
    if (style == AccentStyle.LIGHT) {
        return background(baseColor, shape).then(
            if (borderColor.alpha > 0f && borderWidth > 0.dp) Modifier.border(borderWidth, borderColor, shape)
            else Modifier,
        )
    }
    val washAlpha = accentWashOpacity(style) * if (emphasized) .65f else .35f
    return drawWithCache {
        val fill = shape.createOutline(size, layoutDirection, this)
        val material = accentBrush(accent, style)
        val washAccent = artworkWashAccent(accent)
        val wash = if (washAccent == accent) material else accentBrush(washAccent, style)
        val strokePx = borderWidth.toPx().coerceAtLeast(0f)
        val inset = strokePx / 2f
        val strokeOutline = if (borderColor.alpha > 0f && strokePx > 0f) shape.createOutline(
            Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
            layoutDirection,
            this,
        ) else null
        val stroke = Stroke(strokePx)
        onDrawWithContent {
            drawOutline(fill, baseColor)
            drawOutline(fill, wash, alpha = washAlpha)
            drawContent()
            if (strokeOutline != null) {
                translate(inset, inset) {
                    drawOutline(strokeOutline, material, alpha = borderColor.alpha, style = stroke)
                }
            }
        }
    }
}
