package com.ambient.tv.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
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

/**
 * Cache per surface/track, never globally: ShaderBrush caches its last drawing size and
 * owns the reflection transform. A frame only changes that matrix and draw-time alpha.
 * All gradient stops, including transparent ones, retain the accent's color space.
 */
internal class AccentMotionPaints(private val accent: Color, val style: AccentStyle) {
    private fun blend(target: Color, fraction: Float) = lerp(accent, target.convert(accent.colorSpace), fraction)
    private val light = blend(Color.White, .55f)
    private val clear = light.copy(alpha = 0f)
    // White-on-white has no visible motion on opaque tracks. A tiny shaded reflection
    // preserves the hue/headroom and gives these otherwise flat highlights a visible gesture.
    private val edgeLight = if (accent.luminance() > .8f) blend(Color.Black, .22f) else light
    private val matrix = Matrix()

    private val primary: Brush = when (style) {
        AccentStyle.SATIN -> Brush.linearGradient(
            0f to clear, .20f to clear, .44f to light, .70f to clear, 1f to clear,
        )
        AccentStyle.GLASS -> Brush.verticalGradient(
            0f to edgeLight, .12f to edgeLight.copy(alpha = .55f),
            .42f to edgeLight.copy(alpha = 0f), 1f to edgeLight.copy(alpha = 0f),
        )
        AccentStyle.GLOW -> SolidColor(edgeLight)
        AccentStyle.PEARL -> {
            val cool = blend(Color(0xFFA9C6FF), .45f)
            Brush.linearGradient(0f to cool.copy(alpha = 0f), .28f to cool,
                .66f to cool.copy(alpha = 0f), 1f to cool.copy(alpha = 0f))
        }
        AccentStyle.CERAMIC -> Brush.verticalGradient(0f to edgeLight,
            .12f to edgeLight.copy(alpha = 0f), 1f to edgeLight.copy(alpha = 0f))
        AccentStyle.CHROME -> Brush.linearGradient(
            0f to clear, .41f to clear, .48f to light, .52f to light, .59f to clear, 1f to clear,
        )
        AccentStyle.FACET -> Brush.linearGradient(0f to light, .33f to light, .35f to clear, 1f to clear)
        // A uniform, low-amplitude brightness change preserves Light's solid-color character.
        AccentStyle.LIGHT -> SolidColor(edgeLight)
    }
    private val secondary: Brush? = when (style) {
        AccentStyle.PEARL -> {
            val warm = blend(Color(0xFFFFB9D7), .42f)
            Brush.linearGradient(0f to warm.copy(alpha = 0f), .34f to warm.copy(alpha = 0f),
                .75f to warm, 1f to warm.copy(alpha = 0f))
        }
        AccentStyle.FACET -> Brush.linearGradient(0f to clear, .33f to clear, .35f to light,
            .66f to light, .68f to clear, 1f to clear)
        else -> null
    }
    private val tertiary: Brush? = if (style == AccentStyle.FACET)
        Brush.linearGradient(0f to clear, .66f to clear, .68f to light, 1f to light) else null

    private fun passes(size: Size, frame: AccentMotionFrame?, emphasis: Float, draw: (Brush, Float) -> Unit) {
        if (frame == null || !frame.strength.isFinite() || !frame.phase.isFinite() ||
            frame.strength <= 0f || !emphasis.isFinite() || emphasis <= 0f) return
        val phase = frame.phase.coerceIn(0f, 1f)
        val strength = frame.strength.coerceIn(0f, 1f) * emphasis.coerceIn(0f, 1f)
        when (style) {
            AccentStyle.SATIN, AccentStyle.CHROME, AccentStyle.GLASS -> {
                matrix.reset()
                if (style == AccentStyle.GLASS) matrix.translate(y = size.height * .035f * phase)
                else matrix.translate(x = size.width * .06f * (phase - .5f))
                (primary as ShaderBrush).transform = matrix
                draw(primary, strength * if (style == AccentStyle.CHROME) .14f else .10f)
            }
            AccentStyle.PEARL -> {
                draw(primary, strength * .12f * (1f - phase))
                draw(checkNotNull(secondary), strength * .12f * phase)
            }
            AccentStyle.FACET -> {
                draw(primary, strength * .10f * (1f - phase))
                draw(checkNotNull(secondary), strength * .09f)
                draw(checkNotNull(tertiary), strength * .10f * phase)
            }
            AccentStyle.GLOW -> draw(primary, strength * .08f)
            AccentStyle.CERAMIC -> draw(primary, strength * .10f)
            AccentStyle.LIGHT -> draw(primary, strength * .075f)
        }
    }

    fun drawOutline(
        scope: DrawScope,
        outline: Outline,
        frame: AccentMotionFrame?,
        emphasis: Float = 1f,
        drawStyle: DrawStyle = Fill,
    ) = passes(scope.size, frame, emphasis) { brush, alpha ->
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
        passes(scope.size, frame, .65f) { brush, alpha ->
            scope.drawRoundRect(
                brush = brush,
                topLeft = Offset(if (fromRight) scope.size.width - width else 0f, 0f),
                size = Size(width, scope.size.height),
                cornerRadius = CornerRadius(radius, radius),
                alpha = alpha,
            )
        }
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
