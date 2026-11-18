package com.ambient.tv.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Peak opacity of one style's motion, for a stroke (border) or a fill. Callers scale it by
 * their own emphasis. The border carries each gesture; behind text the surface part stays
 * subtle, because ControlColors reserves this fill peak plus the material wash in its 4.5:1
 * text-contrast budget. Glow animates its halo stroke instead (see InteractionStyle).
 */
internal fun accentMotionPeakAlpha(style: AccentStyle, stroke: Boolean): Float = when (style) {
    AccentStyle.LIGHT -> if (stroke) .28f else .30f
    AccentStyle.SATIN -> if (stroke) .55f else .07f
    AccentStyle.GLASS -> if (stroke) .95f else .10f
    AccentStyle.CHROME -> if (stroke) .90f else .10f
    AccentStyle.GLOW -> 0f
    AccentStyle.PEARL -> if (stroke) .85f else .07f
    AccentStyle.CERAMIC -> if (stroke) .70f else .045f
    AccentStyle.FACET -> if (stroke) .90f else .10f
}

/** Thin progress tracks carry no text: they show the gesture at a share of the border peak. */
private const val TRACK_MOTION_SHARE = .6f

/**
 * Each style has its own subtle gesture, on the border and on the surface:
 * - Light: border and surface breathe once.
 * - Satin: a broad, soft sheen glides across and briefly lifts top and bottom edge.
 * - Glass: a glint runs along the upper edge, like light catching a glass rim.
 * - Chrome (metal): a fast, narrow angled flash.
 * - Glow: nothing here; its halo breathes.
 * - Pearl: the accent's neighbouring hues travel once around the border and flow across.
 * - Ceramic: a soft lamp reflection wanders along the top of the glaze.
 * - Facet: the border's corners and a few planes sparkle one after another.
 * Geometry is in dp, independent of the control's aspect ratio. Brushes that depend on the
 * phase are created per frame; only the focused control (or its card) owns a clock.
 * All gradient stops, including transparent ones, retain the accent's color space.
 */
internal class AccentMotionPaints(private val accent: Color, val style: AccentStyle) {
    private fun blend(target: Color, fraction: Float) = lerp(accent, target.convert(accent.colorSpace), fraction)
    // White-on-white has no visible motion. Very light accents use a slightly shaded light
    // and glint, which keeps every gesture readable (including HDR white).
    private val veryLight = accent.luminance() > .8f
    private val light = if (veryLight) blend(Color.Black, .22f) else blend(Color.White, .55f)
    private val glint = if (veryLight) blend(Color.Black, .22f) else blend(Color.White, .85f)
    private val clearLight = light.copy(alpha = 0f)
    private val clearGlint = glint.copy(alpha = 0f)
    private val cool = accentHueShift(accent, -30f).let { lerp(it, light, .35f) }
    private val warm = accentHueShift(accent, 30f).let { lerp(it, light, .35f) }
    private val solidLight = SolidColor(light)

    private fun passes(
        scope: DrawScope,
        frame: AccentMotionFrame?,
        emphasis: Float,
        stroke: Boolean,
        peak: Float = accentMotionPeakAlpha(style, stroke),
        draw: (Brush, Float) -> Unit,
    ) {
        if (frame == null || !frame.strength.isFinite() || !frame.phase.isFinite() ||
            frame.strength <= 0f || !emphasis.isFinite() || emphasis <= 0f) return
        val w = scope.size.width
        val h = scope.size.height
        if (w <= 0f || h <= 0f) return
        val phase = frame.phase.coerceIn(0f, 1f)
        val alpha = frame.strength.coerceIn(0f, 1f) * emphasis.coerceIn(0f, 1f) * peak
        if (alpha <= 0f) return
        val dp = scope.density
        when (style) {
            AccentStyle.LIGHT -> draw(solidLight, alpha)
            AccentStyle.SATIN -> {
                val band = maxOf(60f * dp, w * .6f)
                val x = -band + phase * (w + band)
                draw(Brush.linearGradient(0f to clearLight, .5f to light, 1f to clearLight,
                    start = Offset(x, 0f), end = Offset(x + band, 0f)), alpha)
            }
            AccentStyle.GLASS -> {
                val radius = 17f * dp
                val x = -radius + phase * (w + 2f * radius)
                draw(Brush.radialGradient(0f to glint, 1f to clearGlint,
                    center = Offset(x, 0f), radius = radius), alpha)
            }
            AccentStyle.CHROME -> {
                val band = 12f * dp
                // Band lines tilted 22° from vertical; the gradient runs along their normal.
                val nx = .927f
                val ny = .375f
                val x = -2f * band + phase * (w + 4f * band)
                val cx = x
                val cy = h / 2f
                draw(Brush.linearGradient(0f to clearGlint, .5f to glint, 1f to clearGlint,
                    start = Offset(cx - nx * band / 2f, cy - ny * band / 2f),
                    end = Offset(cx + nx * band / 2f, cy + ny * band / 2f)), alpha)
            }
            AccentStyle.GLOW -> Unit
            AccentStyle.PEARL -> if (stroke) {
                // One iridescent arc travels once around the outline.
                val steps = 24
                val stops = Array(steps + 1) { i ->
                    val position = i / steps.toFloat()
                    val t = ((position - phase) % 1f + 1f) % 1f
                    position to pearlArc(t)
                }
                draw(Brush.sweepGradient(*stops, center = Offset(w / 2f, h / 2f)), alpha)
            } else {
                val span = w * 2f
                val shift = phase * span
                draw(Brush.linearGradient(
                    0f to cool, .25f to accent, .5f to warm, .75f to accent, 1f to cool,
                    start = Offset(-shift, 0f), end = Offset(-shift + span, 0f),
                    tileMode = TileMode.Repeated,
                ), alpha)
            }
            AccentStyle.CERAMIC -> {
                val radius = minOf(32f * dp, maxOf(h * .65f, 16f * dp))
                val x = -radius + phase * (w + 2f * radius)
                val y = h * .05f - radius * .35f * sin(PI.toFloat() * phase)
                draw(Brush.radialGradient(0f to light, 1f to clearLight,
                    center = Offset(x, y), radius = radius), alpha)
            }
            AccentStyle.FACET -> {
                val points = if (stroke) listOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))
                    else listOf(Offset(w * .25f, h * .6f), Offset(w * .55f, h * .3f), Offset(w * .8f, h * .6f))
                val radius = (if (stroke) 8f else 9f) * dp
                val k = phase * points.size
                val index = k.toInt().coerceIn(0, points.size - 1)
                val flash = sin(PI.toFloat() * (k - index)).let { it * it }
                draw(Brush.radialGradient(0f to glint, 1f to clearGlint,
                    center = points[index], radius = radius), alpha * flash)
            }
        }
    }

    /** Pearl's travelling arc: transparent, cool hue, light, warm hue, transparent. */
    private fun pearlArc(t: Float): Color {
        val stops = PEARL_ARC
        if (t >= stops.last()) return clearLight
        val colors = listOf(cool.copy(alpha = 0f), cool, light, warm, warm.copy(alpha = 0f))
        for (i in 1 until stops.size) {
            if (t <= stops[i]) {
                val f = (t - stops[i - 1]) / (stops[i] - stops[i - 1])
                return lerp(colors[i - 1], colors[i], f.coerceIn(0f, 1f))
            }
        }
        return clearLight
    }

    fun drawOutline(
        scope: DrawScope,
        outline: Outline,
        frame: AccentMotionFrame?,
        emphasis: Float = 1f,
        drawStyle: DrawStyle = Fill,
    ) = passes(scope, frame, emphasis, drawStyle is Stroke) { brush, alpha ->
        scope.drawOutline(outline, brush, alpha = alpha, style = drawStyle)
    }

    fun drawProgress(
        scope: DrawScope,
        frame: AccentMotionFrame?,
        fraction: Float,
        fromRight: Boolean,
        radius: Float,
    ) {
        val bounded = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val width = scope.size.width * bounded
        if (width <= 0f || scope.size.height <= 0f) return
        // Draw exactly the filled geometry in the full-track scope. Neither the gradient's
        // coordinates nor the progress endpoint depends on the decorative phase.
        passes(scope, frame, .75f, stroke = false,
            peak = accentMotionPeakAlpha(style, stroke = true) * TRACK_MOTION_SHARE) { brush, alpha ->
            scope.drawRoundRect(
                brush = brush,
                topLeft = Offset(if (fromRight) scope.size.width - width else 0f, 0f),
                size = Size(width, scope.size.height),
                cornerRadius = CornerRadius(radius, radius),
                alpha = alpha,
            )
        }
    }

    private companion object {
        val PEARL_ARC = floatArrayOf(0f, .11f, .19f, .28f, .39f)
    }
}

internal fun DrawScope.drawAccentProgressMotion(
    paints: AccentMotionPaints,
    motion: AccentMotionFrame?,
    fraction: Float,
    fromRight: Boolean = false,
    radius: Float = size.height / 2f,
) = paints.drawProgress(this, motion, fraction, fromRight, radius)

/** A material gesture for controls whose base surface is painted by Material 3 itself. */
internal fun Modifier.accentMotionOutline(shape: Shape, accent: Color, width: Dp = 2.dp): Modifier = composed {
    val motion = LocalAccentMotionState.current
    val materialStyle = LocalAccentStyle.current
    if (motion == null) this else drawWithCache {
        val strokePx = width.toPx()
        val outline = shape.createOutline(
            Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
            layoutDirection, this,
        )
        val paints = AccentMotionPaints(accent, materialStyle)
        val stroke = Stroke(strokePx)
        onDrawWithContent {
            drawContent()
            if (strokePx > 0f) translate(strokePx / 2f, strokePx / 2f) {
                paints.drawOutline(this, outline, motion.value, drawStyle = stroke)
            }
        }
    }
}
