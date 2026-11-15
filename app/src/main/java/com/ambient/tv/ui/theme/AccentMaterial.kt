package com.ambient.tv.ui.theme

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/**
 * Draw inside a track-sized scope. The brush keeps the full track's coordinates while only
 * the filled geometry grows, so a stationary reflection never slides as playback advances.
 */
internal fun DrawScope.drawAccentProgress(
    brush: Brush,
    fraction: Float,
    fromRight: Boolean = false,
    radius: Float = size.height / 2f,
) {
    val bounded = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val fillWidth = size.width * bounded
    if (fillWidth <= 0f || size.height <= 0f) return
    drawRoundRect(
        brush = brush,
        topLeft = Offset(if (fromRight) size.width - fillWidth else 0f, 0f),
        size = Size(fillWidth, size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}

/**
 * Small reusable material treatment for buttons and controls. Fill/stroke use
 * cached shaders instead of a blur or a new offscreen layer for each control.
 * Light leaves the original flat background/border rendering untouched.
 */
fun Modifier.accentMaterialFill(
    color: Color,
    shape: Shape,
    accent: Color,
    active: Boolean,
): Modifier = composed {
    val style = LocalAccentStyle.current
    val motion = LocalAccentMotionState.current
    if (style == AccentStyle.LIGHT && motion == null) {
        this.background(color, shape)
    } else {
        val reveal by animateFloatAsState(
            if (active) 1f else 0f,
            tween(145, easing = LinearOutSlowInEasing), label = "accent material fill",
        )
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val material = accentBrush(accent, style)
            val motionPaints = if (motion != null) AccentMotionPaints(accent, style) else null
            onDrawBehind {
                drawOutline(outline, color)
                if (style != AccentStyle.LIGHT && reveal > 0f) {
                    drawOutline(outline, material, alpha = reveal * accentWashOpacity(style))
                }
                motionPaints?.drawOutline(this, outline, motion?.value, emphasis = .25f * reveal)
            }
        }
    }
}

fun Modifier.accentMaterialBorder(
    width: Dp,
    color: Color,
    shape: Shape,
    accent: Color,
    active: Boolean,
): Modifier = composed {
    val style = LocalAccentStyle.current
    val reduced = LocalPerformanceProfile.current.reducedEffects
    val motion = LocalAccentMotionState.current
    if (style == AccentStyle.LIGHT && motion == null) {
        this.border(width, color, shape)
    } else {
        this.drawWithCache {
            val strokePx = width.toPx()
            val inset = strokePx / 2f
            val outline = shape.createOutline(
                Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
                layoutDirection, this,
            )
            val material = accentBrush(accent, style)
            val stroke = Stroke(strokePx)
            val glowStroke = if (style == AccentStyle.GLOW && active && !reduced) Stroke(strokePx * 2.5f) else null
            val motionPaints = if (motion != null) AccentMotionPaints(accent, style) else null
            onDrawWithContent {
                drawContent()
                if (color.alpha > 0f && strokePx > 0f) {
                    val frame = motion?.value
                    translate(inset, inset) {
                        if (glowStroke != null) {
                            drawOutline(outline, material, alpha = color.alpha * (.12f + .025f * (frame?.strength ?: 0f)),
                                style = glowStroke)
                        }
                        if (style == AccentStyle.LIGHT) drawOutline(outline, color, style = stroke)
                        else drawOutline(outline, material, alpha = color.alpha, style = stroke)
                        motionPaints?.drawOutline(this, outline, frame, emphasis = color.alpha, drawStyle = stroke)
                    }
                }
            }
        }
    }
}
