package com.ambient.tv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/** A neutral focus signal remains visible with even a black or white custom accent. */
@Immutable
data class InteractionColors(val container: Color, val outline: Color, val content: Color)

/** Selected state is persistent; focus remains a separate neutral boundary. */
enum class SelectionIndicator { UNDERLINE, CHIP_UNDERLINE, LEADING_BAR, DOT, NONE }

/**
 * Fixed, inset geometry prevents layout shifts and keeps the selection mark separate
 * from the focus outline. Pure geometry is shared by TV and handheld controls.
 */
internal fun selectionIndicatorRect(
    selected: Boolean,
    surface: Size,
    indicator: SelectionIndicator,
    dp: Float,
    rtl: Boolean = false,
): Rect? {
    if (!selected || indicator == SelectionIndicator.NONE || dp <= 0f ||
        !surface.width.isFinite() || !surface.height.isFinite()) return null
    val inset = 7f * dp
    val roomW = surface.width - 2f * inset
    val roomH = surface.height - 2f * inset
    if (roomW <= 0f || roomH <= 0f) return null
    val thickness = minOf((if (indicator == SelectionIndicator.CHIP_UNDERLINE) 2f else 1.75f) * dp,
        roomW * .25f, roomH * .25f)
    return when (indicator) {
        SelectionIndicator.UNDERLINE, SelectionIndicator.CHIP_UNDERLINE -> {
            // Text chips carry a longer persistent selection mark; compact OSD actions do not.
            val chip = indicator == SelectionIndicator.CHIP_UNDERLINE
            val width = minOf((if (chip) 40f else 24f) * dp, roomW * if (chip) .60f else .50f)
            val left = (surface.width - width) / 2f
            Rect(left, surface.height - inset - thickness,
                left + width, surface.height - inset)
        }
        SelectionIndicator.LEADING_BAR -> {
            val length = minOf(23f * dp, roomH * .68f)
            val left = if (rtl) surface.width - inset - thickness else inset
            val top = (surface.height - length) / 2f
            Rect(left, top, left + thickness, top + length)
        }
        SelectionIndicator.DOT -> {
            val diameter = minOf(5f * dp, roomW * .30f, roomH * .50f)
            val left = if (rtl) inset else surface.width - inset - diameter
            Rect(left, inset, left + diameter, inset + diameter)
        }
        SelectionIndicator.NONE -> null
    }
}

/**
 * Reveal only the marker's long axis, symmetrically from its center. Dots expand on
 * both axes. The full rect never changes, keeping all layout and RTL offsets stable.
 */
internal fun selectionIndicatorRevealRect(
    bounds: Rect?,
    indicator: SelectionIndicator,
    progress: Float,
): Rect? {
    if (bounds == null || !progress.isFinite() || progress <= 0f) return null
    val fraction = progress.coerceIn(0f, 1f)
    return when (indicator) {
        SelectionIndicator.UNDERLINE, SelectionIndicator.CHIP_UNDERLINE -> {
            val halfWidth = bounds.width * fraction / 2f
            Rect(bounds.center.x - halfWidth, bounds.top, bounds.center.x + halfWidth, bounds.bottom)
        }
        SelectionIndicator.LEADING_BAR -> {
            val halfHeight = bounds.height * fraction / 2f
            Rect(bounds.left, bounds.center.y - halfHeight, bounds.right, bounds.center.y + halfHeight)
        }
        SelectionIndicator.DOT -> {
            val halfWidth = bounds.width * fraction / 2f
            val halfHeight = bounds.height * fraction / 2f
            Rect(bounds.center.x - halfWidth, bounds.center.y - halfHeight,
                bounds.center.x + halfWidth, bounds.center.y + halfHeight)
        }
        SelectionIndicator.NONE -> null
    }
}

private fun DrawScope.drawSelectionIndicator(
    bounds: Rect?,
    brush: Brush,
    indicator: SelectionIndicator,
    progress: Float,
    onLightBackground: Boolean = false,
) {
    val revealed = selectionIndicatorRevealRect(bounds, indicator, progress) ?: return
    val radius = CornerRadius(minOf(revealed.width, revealed.height) / 2f)
    // On the light sidebar focus pill, avoid an expanded dark halo plus a bright
    // material border. Keep the persistent selection mark thin and restrained.
    if (onLightBackground) {
        drawRoundRect(color = Color.Black.copy(alpha = .42f),
            topLeft = revealed.topLeft, size = revealed.size, cornerRadius = radius)
        drawRoundRect(brush = brush, alpha = .30f,
            topLeft = revealed.topLeft, size = revealed.size, cornerRadius = radius)
    } else {
        drawRoundRect(brush = brush, alpha = .80f,
            topLeft = revealed.topLeft, size = revealed.size, cornerRadius = radius)
    }
}

/** Selected state uses one finite animation; no separate timer or layout invalidation. */
internal fun selectionIndicatorMotionDurationMs(
    selecting: Boolean,
    focusPulse: Boolean = false,
    compact: Boolean = false,
): Int = when {
    compact && focusPulse -> 110
    compact && selecting -> 140
    compact -> 100
    focusPulse -> 180
    selecting -> 220
    else -> 160
}

private class SelectionIndicatorHistory(var selected: Boolean, var focused: Boolean)

/**
 * The initial marker is drawn at its final state, avoiding a reveal when a cached
 * screen first appears. Only later selection/focus changes animate the drawing.
 * Sparsam and Auto-on-low-end snap immediately, without requesting animation frames.
 */
@Composable
private fun rememberSelectionIndicatorProgress(
    selected: Boolean,
    focused: Boolean,
    compact: Boolean = false,
): State<Float> {
    val allowMotion = LocalPerformanceProfile.current.allowSelectionIndicatorMotion
    val marker = remember { Animatable(if (selected) 1f else 0f) }
    val history = remember { SelectionIndicatorHistory(selected, focused) }
    LaunchedEffect(selected, focused, compact, allowMotion) {
        val wasSelected = history.selected
        val gainedFocus = focused && !history.focused
        history.selected = selected
        history.focused = focused
        val target = if (selected) 1f else 0f
        when {
            !allowMotion -> marker.snapTo(target)
            wasSelected != selected -> marker.animateTo(
                target,
                tween(selectionIndicatorMotionDurationMs(selected, compact = compact),
                    easing = FastOutSlowInEasing),
            )
            selected && gainedFocus && marker.value >= .999f -> {
                // Already selected: briefly contract, then reveal the full fixed-width marker.
                marker.snapTo(.625f)
                marker.animateTo(1f, tween(selectionIndicatorMotionDurationMs(
                    selecting = true, focusPulse = true, compact = compact),
                    easing = FastOutSlowInEasing))
            }
            // A rapid focus exit must finish any in-flight reveal rather than freeze halfway.
            marker.value != target -> marker.animateTo(
                target,
                tween(selectionIndicatorMotionDurationMs(
                    selecting = selected, focusPulse = selected, compact = compact),
                    easing = FastOutSlowInEasing),
            )
        }
    }
    return marker.asState()
}

/** Extra selection treatment for surfaces that already own their focus drawing. */
fun Modifier.selectedStateMarker(
    selected: Boolean,
    indicator: SelectionIndicator = SelectionIndicator.LEADING_BAR,
    focusedOnLightSurface: Boolean = false,
    focused: Boolean = false,
    compactMotion: Boolean = false,
): Modifier = composed {
    val accent = MaterialTheme.colorScheme.primary
    val style = LocalAccentStyle.current
    val progress = rememberSelectionIndicatorProgress(selected, focused, compactMotion)
    // Keep this draw node while deselecting, so the old marker can shrink to zero.
    this.drawWithCache {
        val bounds = selectionIndicatorRect(true, size, indicator, 1.dp.toPx(), layoutDirection == LayoutDirection.Rtl)
        val brush = accentBrush(accent, style)
        onDrawWithContent {
            drawContent()
            drawSelectionIndicator(bounds, brush, indicator, progress.value, focusedOnLightSurface)
        }
    }
}

@Composable
fun interactionColors(
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    animate: Boolean? = null,
    prominent: Boolean = false,
    pressed: Boolean = false,
): InteractionColors {
    val state = rememberInteractionColorState(
        focused, selected, enabled, animate, prominent = prominent, pressed = pressed,
    )
    return InteractionColors(state.container.value, state.outline.value, state.content)
}

private class InteractionColorState(
    val container: State<Color>,
    val outline: State<Color>,
    val washOpacity: State<Float>,
    val palette: ControlPalette,
    val content: Color,
)

@Composable
private fun rememberInteractionColorState(
    focused: Boolean,
    selected: Boolean,
    enabled: Boolean,
    animate: Boolean?,
    focusedAccent: Boolean = false,
    prominent: Boolean = false,
    pressed: Boolean = false,
): InteractionColorState {
    val scheme = MaterialTheme.colorScheme
    val materialStyle = LocalAccentStyle.current
    val hdrHeadroom = com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f
    val sharedPalette = LocalControlPalette.current?.takeIf { palette ->
        val input = palette.input
        input.surface == scheme.surface && input.raisedSurface == scheme.surfaceVariant &&
            input.content == scheme.onSurface && input.mutedContent == scheme.onSurfaceVariant &&
            input.accent == scheme.primary && input.style == materialStyle && input.headroom == hdrHeadroom
    }
    // Normal app controls share the palette prepared by AmbientTvTheme. A nested theme
    // or isolated preview gets a local cache keyed by its actual colors.
    val palette = sharedPalette ?: remember(
        scheme.surface, scheme.surfaceVariant, scheme.onSurface, scheme.onSurfaceVariant,
        scheme.primary, materialStyle, hdrHeadroom,
    ) {
        controlPalette(
            surface = scheme.surface,
            raisedSurface = scheme.surfaceVariant,
            content = scheme.onSurface,
            mutedContent = scheme.onSurfaceVariant,
            accent = scheme.primary,
            style = materialStyle,
            headroom = hdrHeadroom,
        )
    }
    val target = palette.colors(focused, selected, enabled, prominent, pressed, focusedAccent)
    val animationSpec = if (animate == false) snap() else focusColorSpec()
    // Brightness changes must settle together: fading an old HDR surface after its
    // content has already returned to SDR could briefly reduce the text contrast.
    return key(hdrHeadroom) {
        val container = animateColorAsState(
            target.container, animationSpec, label = "control surface",
        )
        val outline = animateColorAsState(
            target.outline, animationSpec, label = "control outline",
        )
        val washOpacity = animateFloatAsState(
            target.washOpacity,
            animationSpec = when {
                animate == false -> snap()
                LocalPerformanceProfile.current.allowSpringFocus -> tween(LocalUiTokens.current.animationFastMs)
                else -> tween(REDUCED_FOCUS_FADE_MS, easing = LinearOutSlowInEasing)
            },
            label = "control material wash",
        )
        InteractionColorState(container, outline, washOpacity, palette, target.content)
    }
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
    focusedAccent: Boolean = false,
    prominent: Boolean = false,
    pressed: Boolean = false,
    selectionIndicator: SelectionIndicator = SelectionIndicator.UNDERLINE,
): Modifier = composed {
    val colors = rememberInteractionColorState(
        focused, selected, enabled, animate, focusedAccent, prominent, pressed,
    )
    val materialStyle = LocalAccentStyle.current
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val borderWidth = LocalUiTokens.current.focusBorder
    // A parent may own the shared clock (e.g. the whole OSD). Only its actually
    // focused child may paint a moving outline; selected-only/idle remain stable.
    val motion = rememberAccentMotionState(focused && enabled).takeIf { focused && enabled }
    val markerProgress = rememberSelectionIndicatorProgress(selected && enabled, focused && enabled)
    this.drawWithCache {
        val strokePx = borderWidth.toPx()
        val fill = shape.createOutline(size, layoutDirection, this)
        val inset = strokePx / 2f
        val strokeOutline = shape.createOutline(
            Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
            layoutDirection,
            this,
        )
        // Keep selected-only outlines slimmer than the active focus boundary.
        val stroke = Stroke(if (selected && !focused) strokePx * .68f else strokePx)
        val motionStroke = Stroke(strokePx * 2f)
        val material = colors.palette.materialBrush()
        val focusBorder = colors.palette.focusBorderBrush()
        val selectedBorder = colors.palette.selectedBorderBrush()
        val marker = selectionIndicatorRect(
            true, size, selectionIndicator, 1.dp.toPx(),
            layoutDirection == LayoutDirection.Rtl,
        )
        val glowStroke = if (materialStyle == AccentStyle.GLOW && !reducedEffects && focused && enabled)
            Stroke(strokePx * 2.5f) else null
        val motionPaints = if (motion != null && enabled)
            AccentMotionPaints(colors.palette.input.accent, materialStyle) else null
        onDrawBehind {
            val frame = motion?.value
            drawOutline(fill, colors.container.value)
            val wash = colors.washOpacity.value
            if (wash > 0f) {
                drawOutline(fill, material, alpha = wash)
            }
            // The palette reserves this additional wash before any draw-time animation.
            motionPaints?.drawOutline(this, fill, frame, emphasis = CONTROL_ACCENT_MOTION_EMPHASIS)
            val outline = colors.outline.value
            if (outline.alpha > 0f && strokePx > 0f) {
                translate(inset, inset) {
                    if (glowStroke != null) {
                        drawOutline(strokeOutline, material,
                            alpha = outline.alpha * (.10f + .02f * (frame?.strength ?: 0f)),
                            style = glowStroke)
                    }
                    // Draw the chosen material *before* the motion. Previously the
                    // opaque base stroke hid almost the entire animated reflection.
                    // Selected-only controls get a quieter thin material stroke,
                    // rather than the same plain colored border for every style.
                    if (enabled && materialStyle != AccentStyle.LIGHT && (focused || selected)) {
                        drawOutline(strokeOutline,
                            if (focused) focusBorder else selectedBorder,
                            alpha = outline.alpha, style = stroke)
                    } else {
                        drawOutline(strokeOutline, outline, style = stroke)
                    }
                    if (materialStyle != AccentStyle.LIGHT) {
                        // A narrow traveling halo makes the opt-in gesture readable
                        // from TV distance without changing layout or idle chrome.
                        motionPaints?.drawOutline(this, strokeOutline, frame,
                            emphasis = outline.alpha * .45f, drawStyle = motionStroke)
                    }
                    motionPaints?.drawOutline(this, strokeOutline, frame,
                        emphasis = outline.alpha, drawStyle = stroke)
                }
            }
            // The selection marker expands/collapses inside its fixed geometry; its animation
            // only invalidates drawing, never the control's layout or the focus boundary.
            drawSelectionIndicator(marker, material, selectionIndicator, markerProgress.value)
        }
    }
}

/**
 * OSD-specific palette treatment shared by Media3, MPV, VLC, LiveTV, radio, weather,
 * photos, SmartTube and Monogram. Selection and focus never replace one another.
 */
fun Modifier.appOsdControlSurface(
    focused: Boolean,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape,
    prominent: Boolean = false,
    pressed: Boolean = false,
    selectionIndicator: SelectionIndicator = SelectionIndicator.DOT,
): Modifier = appControlSurface(
    focused = focused, selected = selected, enabled = enabled, shape = shape,
    // Focus is shown by the outer accent border/material motion, never an extra line
    // inside an OSD button. Selected states keep their separate marker.
    prominent = prominent, pressed = pressed, selectionIndicator = selectionIndicator,
)

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


private const val REDUCED_FOCUS_FADE_MS = 140

/**
 * The focus target changes immediately on D-pad input; only the border's draw color animates.
 * A lightweight fade is safe even with reduced UI effects because it never changes layout size,
 * creates a shadow, or allocates an additional compositing layer.
 */
internal fun focusBorderFadeDurationMs(focused: Boolean, reducedEffects: Boolean): Int =
    if (reducedEffects) {
        if (focused) 140 else 110
    } else {
        if (focused) 160 else 130
    }

/**
 * A constant-width focus outline for browsable cards and rows. Keeping one fixed stroke avoids
 * the abrupt 1dp-to-2dp change (and its subpixel shimmer) when the remote moves focus.
 *
 * The animation state is read only while drawing, not during composition of poster content;
 * rapid focus hops reverse the fade without leaving an old outline stuck on a card.
 */
fun Modifier.animatedFocusBorder(
    focused: Boolean,
    shape: Shape,
    focusedColor: Color,
    unfocusedColor: Color = Color.Transparent,
    width: Dp? = null,
): Modifier = composed {
    val strokeWidth = width ?: LocalUiTokens.current.focusBorder
    val reduced = LocalPerformanceProfile.current.reducedEffects
    val materialStyle = LocalAccentStyle.current
    val motion = rememberAccentMotionState(focused).takeIf { focused }
    // The outline's opacity fades on focus hops; its hue also crossfades when the user
    // changes the configured accent while a card is already focused.
    val accent = animateColorAsState(
        targetValue = focusedColor,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "focus outline accent",
    )
    val reveal = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = focusBorderFadeDurationMs(focused, reduced),
            easing = LinearOutSlowInEasing,
        ),
        label = "focus outline fade",
    )
    this.drawWithCache {
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val outline = shape.createOutline(
            Size((size.width - strokePx).coerceAtLeast(0f),
                (size.height - strokePx).coerceAtLeast(0f)),
            layoutDirection,
            this,
        )
        val stroke = Stroke(strokePx)
        val motionStroke = Stroke(strokePx * 2f)
        val material = accentBrush(focusedColor, materialStyle)
        val focusBorder = accentFocusBorderBrush(focusedColor, materialStyle)
        val glowStroke = if (materialStyle == AccentStyle.GLOW && !reduced) Stroke(strokePx * 2.6f) else null
        val motionPaints = if (motion != null) AccentMotionPaints(focusedColor, materialStyle) else null
        onDrawWithContent {
            drawContent()
            if (strokePx > 0f) {
                val color = lerp(unfocusedColor, accent.value, reveal.value)
                if (color.alpha > 0f) {
                    val frame = motion?.value
                    translate(inset, inset) {
                        if (materialStyle == AccentStyle.LIGHT) {
                            drawOutline(outline, color, style = stroke)
                        } else {
                            if (glowStroke != null) {
                                drawOutline(outline, material, alpha = color.alpha * (.13f + .025f * (frame?.strength ?: 0f)),
                                    style = glowStroke)
                            }
                            drawOutline(outline, focusBorder, alpha = color.alpha, style = stroke)
                            motionPaints?.drawOutline(this, outline, frame,
                                emphasis = color.alpha * .45f, drawStyle = motionStroke)
                        }
                        motionPaints?.drawOutline(this, outline, frame, emphasis = color.alpha, drawStyle = stroke)
                    }
                }
            }
        }
    }
}


/**
 * Fade the surface wash and stroke together when the focused TV item changes.
 * The border has fixed geometry, and animated colors are only read in draw,
 * preserving media focus scaling and avoiding poster/layout recomposition.
 */
fun Modifier.animatedFocusSurface(
    focused: Boolean,
    shape: Shape,
    focusedBackground: Color,
    unfocusedBackground: Color,
    focusedBorder: Color,
    unfocusedBorder: Color = Color.Transparent,
    borderWidth: Dp = 2.dp,
    /** False for screens whose focus boundary is intentionally neutral/white. */
    focusBorderMaterial: Boolean = true,
    /** Persistent selection retains a thinner material outline even when not focused. */
    selected: Boolean = false,
): Modifier = composed {
    val reduced = LocalPerformanceProfile.current.reducedEffects
    val materialStyle = LocalAccentStyle.current
    val primaryAccent = MaterialTheme.colorScheme.primary
    val motion = rememberAccentMotionState(focused).takeIf { focused }
    val fade = tween<Color>(
        durationMillis = focusBorderFadeDurationMs(focused, reduced),
        easing = LinearOutSlowInEasing,
    )
    val fill = animateColorAsState(
        targetValue = if (focused) focusedBackground else unfocusedBackground,
        animationSpec = fade,
        label = "focus surface fill",
    )
    val strokeColor = animateColorAsState(
        targetValue = if (focused) focusedBorder else unfocusedBorder,
        animationSpec = fade,
        label = "focus surface stroke",
    )
    val materialReveal = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = focusBorderFadeDurationMs(focused, reduced),
            easing = LinearOutSlowInEasing,
        ),
        label = "focus surface material",
    )
    this.drawWithCache {
        val strokePx = borderWidth.toPx()
        val full = shape.createOutline(size, layoutDirection, this)
        val inset = strokePx / 2f
        val inner = shape.createOutline(
            Size(
                (size.width - strokePx).coerceAtLeast(0f),
                (size.height - strokePx).coerceAtLeast(0f),
            ),
            layoutDirection,
            this,
        )
        val material = accentBrush(primaryAccent, materialStyle)
        val focusBorder = accentFocusBorderBrush(focusedBorder.copy(alpha = 1f), materialStyle)
        val selectedBorder = accentFocusBorderBrush(unfocusedBorder.copy(alpha = 1f), materialStyle)
        val accentOutline = focusBorderMaterial
        val stroke = Stroke(strokePx)
        val motionStroke = Stroke(strokePx * 2f)
        val glowStroke = if (materialStyle == AccentStyle.GLOW && !reduced) Stroke(strokePx * 2.5f) else null
        val motionPaints = if (motion != null) AccentMotionPaints(primaryAccent, materialStyle) else null
        onDrawWithContent {
            val frame = motion?.value
            drawOutline(full, fill.value)
            if (materialStyle != AccentStyle.LIGHT && materialReveal.value > 0f) {
                drawOutline(full, material, alpha = accentWashOpacity(materialStyle) * materialReveal.value)
            }
            motionPaints?.drawOutline(this, full, frame, emphasis = CONTROL_ACCENT_MOTION_EMPHASIS * materialReveal.value)
            drawContent()
            if (strokePx > 0f && strokeColor.value.alpha > 0f) {
                translate(inset, inset) {
                    if (materialStyle == AccentStyle.LIGHT || !accentOutline) {
                        drawOutline(inner, strokeColor.value, style = stroke)
                    } else if (!focused) {
                        // Only explicitly selected rows receive a persistent material
                        // stroke; ordinary idle borders retain their original color.
                        if (selected) drawOutline(inner, selectedBorder,
                            alpha = strokeColor.value.alpha, style = stroke)
                        else drawOutline(inner, strokeColor.value, style = stroke)
                    } else {
                        if (glowStroke != null) {
                            drawOutline(inner, material, alpha = strokeColor.value.alpha * (.12f + .025f * (frame?.strength ?: 0f)) * materialReveal.value,
                                style = glowStroke)
                        }
                        drawOutline(inner, focusBorder, alpha = strokeColor.value.alpha, style = stroke)
                        motionPaints?.drawOutline(this, inner, frame,
                            emphasis = strokeColor.value.alpha * .45f, drawStyle = motionStroke)
                        motionPaints?.drawOutline(this, inner, frame, emphasis = strokeColor.value.alpha, drawStyle = stroke)
                    }
                }
            }
        }
    }
}

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
