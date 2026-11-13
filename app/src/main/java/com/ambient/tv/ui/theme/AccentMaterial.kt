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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import com.ambient.tv.ui.performance.LocalPerformanceProfile

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
    if (style == AccentStyle.LIGHT) {
        this.background(color, shape)
    } else {
        val reveal by animateFloatAsState(
            if (active) 1f else 0f,
            tween(145, easing = LinearOutSlowInEasing), label = "accent material fill",
        )
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val material = accentBrush(accent, style)
            onDrawBehind {
                drawOutline(outline, color)
                if (reveal > 0f) {
                    drawOutline(outline, material, alpha = reveal * accentWashOpacity(style))
                }
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
    if (style == AccentStyle.LIGHT) {
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
            onDrawWithContent {
                drawContent()
                if (color.alpha > 0f && strokePx > 0f) {
                    translate(inset, inset) {
                        if (style == AccentStyle.GLOW && active && !reduced) {
                            drawOutline(outline, material, alpha = color.alpha * .12f,
                                style = Stroke(strokePx * 2.5f))
                        }
                        drawOutline(outline, material, alpha = color.alpha, style = Stroke(strokePx))
                    }
                }
            }
        }
    }
}
