package com.ambient.tv.ui.theme

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val retained = remember { AccentMotionFrameRetention() }
    val materialAccent = materialAccentFor(accent)
    val reduced = LocalPerformanceProfile.current.reducedEffects
    val reveal by animateFloatAsState(
        if (active) 1f else 0f,
        tween(if (reduced) 120 else if (active) 230 else 210,
            easing = LinearOutSlowInEasing),
        label = "accent material fill",
    )
    if (style == AccentStyle.LIGHT && motion == null && retained.lastFrame.strength <= 0f) {
        this.background(color, shape)
    } else {
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val material = accentBrush(materialAccent, style)
            val motionPaints = if (motion != null || retained.lastFrame.strength > 0f)
                AccentMotionPaints(materialAccent, style) else null
            onDrawBehind {
                val frame = transitionedAccentMotionFrame(motion?.value, retained, reveal)
                drawOutline(outline, color)
                if (style != AccentStyle.LIGHT && reveal > 0f) {
                    drawOutline(outline, material, alpha = reveal * accentWashOpacity(style))
                }
                motionPaints?.drawOutline(this, outline, frame,
                    emphasis = CONTROL_ACCENT_MOTION_EMPHASIS * reveal)
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
    val retained = remember { AccentMotionFrameRetention() }
    val materialAccent = materialAccentFor(accent)
    val reveal by animateFloatAsState(
        if (active) 1f else 0f,
        tween(if (reduced) 120 else if (active) 230 else 210,
            easing = LinearOutSlowInEasing),
        label = "accent material border",
    )
    if (style == AccentStyle.LIGHT && motion == null && retained.lastFrame.strength <= 0f) {
        this.border(width, color, shape)
    } else {
        this.drawWithCache {
            val strokePx = width.toPx()
            val inset = strokePx / 2f
            val outline = shape.createOutline(
                Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
                layoutDirection, this,
            )
            val material = accentBrush(materialAccent, style)
            val focusBorder = accentFocusBorderBrush(materialAccent, style)
            val stroke = Stroke(strokePx)
            val glowStroke = if (style == AccentStyle.GLOW && !reduced) Stroke(strokePx * 2.5f) else null
            val motionPaints = if (motion != null || retained.lastFrame.strength > 0f)
                AccentMotionPaints(materialAccent, style) else null
            onDrawWithContent {
                drawContent()
                if (color.alpha > 0f && strokePx > 0f) {
                    val frame = transitionedAccentMotionFrame(motion?.value, retained, reveal)
                    translate(inset, inset) {
                        if (glowStroke != null && reveal > 0f) {
                            drawOutline(outline, material,
                                alpha = color.alpha * (.12f + .11f * (frame?.strength ?: 0f)) * reveal,
                                style = glowStroke)
                        }
                        if (style == AccentStyle.LIGHT) drawOutline(outline, color, style = stroke)
                        else {
                            if (reveal < 1f) drawOutline(outline, color, alpha = 1f - reveal, style = stroke)
                            if (reveal > 0f) drawOutline(outline, focusBorder,
                                alpha = color.alpha * reveal, style = stroke)
                        }
                        motionPaints?.drawOutline(this, outline, frame,
                            emphasis = color.alpha * reveal, drawStyle = stroke)
                    }
                }
            }
        }
    }
}
