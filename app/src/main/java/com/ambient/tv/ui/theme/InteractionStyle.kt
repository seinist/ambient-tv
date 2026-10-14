package com.ambient.tv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/** A neutral focus signal remains visible with even a black or white custom accent. */
@Immutable
data class InteractionColors(val container: Color, val outline: Color, val content: Color)

@Composable
fun interactionColors(
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    animate: Boolean? = null,
): InteractionColors {
    val state = rememberInteractionColorState(focused, selected, enabled, animate)
    return InteractionColors(state.container.value, state.outline(), state.content)
}

private class InteractionColorState(
    val container: State<Color>,
    private val outlineState: State<Color>,
    private val highlight: Float,
    val content: Color,
) {
    fun outline(): Color = com.ambient.tv.ui.display.uiHdrHighlight(outlineState.value, highlight)
}

@Composable
private fun rememberInteractionColorState(
    focused: Boolean,
    selected: Boolean,
    enabled: Boolean,
    animate: Boolean?,
): InteractionColorState {
    val scheme = MaterialTheme.colorScheme
    val animationSpec = if (animate == false) snap() else focusColorSpec()
    val container = animateColorAsState(
        when {
            !enabled -> scheme.surfaceVariant.copy(alpha = .30f)
            focused -> lerp(scheme.surfaceVariant, scheme.onSurface, .12f)
            selected -> lerp(scheme.surface, scheme.primary, .16f)
            else -> scheme.surface.copy(alpha = .68f)
        }, animationSpec, label = "control surface",
    )
    val outline = animateColorAsState(
        when {
            focused -> scheme.onSurface.copy(alpha = .90f)
            selected -> accessibleAccent(scheme.primary, scheme.surface).copy(alpha = .70f)
            else -> Color.Transparent
        }, animationSpec, label = "control outline",
    )
    val hdrHeadroom = com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f
    return InteractionColorState(
        container,
        outline,
        if (focused && enabled) hdrHeadroom else 1f,
        if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = .55f),
    )
}

/**
 * Animated colors are read only in the draw phase, so a focus fade redraws the surface
 * without recomposing the row on every frame.
 */
fun Modifier.appControlSurface(
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape,
    animate: Boolean? = null,
): Modifier = composed {
    val colors = rememberInteractionColorState(focused, selected, enabled, animate)
    val borderWidth = LocalUiTokens.current.focusBorder
    this.drawWithCache {
        val strokePx = borderWidth.toPx()
        val fill = shape.createOutline(size, layoutDirection, this)
        val inset = strokePx / 2f
        val strokeOutline = shape.createOutline(
            Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
            layoutDirection,
            this,
        )
        val stroke = Stroke(strokePx)
        onDrawBehind {
            drawOutline(fill, colors.container.value)
            val outline = colors.outline()
            if (outline.alpha > 0f && strokePx > 0f) {
                translate(inset, inset) { drawOutline(strokeOutline, outline, style = stroke) }
            }
        }
    }
}

/**
 * Focus color crossfade. Reduced-performance profiles still fade (a short color tween only
 * touches draw, no layout or layer), so focus hops between rows don't flicker hard.
 */
@Composable
fun focusColorSpec(): AnimationSpec<Color> {
    val tokens = LocalUiTokens.current
    return if (LocalPerformanceProfile.current.allowSpringFocus) {
        tween(tokens.animationFastMs)
    } else {
        tween(REDUCED_FOCUS_FADE_MS, easing = LinearOutSlowInEasing)
    }
}

private const val REDUCED_FOCUS_FADE_MS = 90

/** Subtle TV-menu focus motion; reduced-performance profiles keep the focus state static. */
fun Modifier.menuFocusMotion(focused: Boolean, focusedScale: Float = 1.012f): Modifier = composed {
    val motionEnabled = LocalPerformanceProfile.current.allowSpringFocus
    val scale = animateFloatAsState(
        targetValue = if (motionEnabled && focused) focusedScale else 1f,
        animationSpec = if (motionEnabled) {
            tween(
                durationMillis = if (focused) 140 else 110,
                easing = FastOutSlowInEasing,
            )
        } else {
            snap()
        },
        label = "menuFocusScale",
    )
    this
        .zIndex(if (motionEnabled && focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

fun contrastRatio(a: Color, b: Color): Float {
    val high = maxOf(a.luminance(), b.luminance())
    val low = minOf(a.luminance(), b.luminance())
    return (high + .05f) / (low + .05f)
}

/** Preserve the chosen hue, lifting only accents that disappear into the dark surface. */
fun accessibleAccent(accent: Color, surface: Color): Color {
    val opaque = accent.copy(alpha = 1f)
    if (contrastRatio(opaque, surface) >= 4.5f) return opaque
    for (step in 1..20) {
        val lifted = lerp(opaque, Color.White, step / 20f)
        if (contrastRatio(lifted, surface) >= 4.5f) return lifted
    }
    return Color.White
}

fun contentOnAccent(accent: Color): Color =
    if (contrastRatio(Color.Black, accent) >= contrastRatio(Color.White, accent)) Color.Black else Color.White
