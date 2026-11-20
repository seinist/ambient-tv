package com.ambient.tv.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/** The historic atomic TV navigation remains the default. Never animate video surfaces. */
enum class ViewTransitionStyle(val key: String) {
    OFF("off"),
    SOFT_FADE("fade"),
    MICRO_SLIDE("slide"),
    CINEMATIC("cinematic");

    companion object {
        fun fromKey(key: String?): ViewTransitionStyle =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * Only the active NavHost destination is drawn. In particular CINEMATIC is a
 * single-page cinematic blend, not a simultaneous rendering of two 4K grids:
 * outgoing Compose destinations, their focus targets and BackHandlers are disposed
 * immediately, and the incoming destination's presentation eases in above them.
 */
internal data class ViewTransitionVisual(
    val durationMs: Int = 0,
    val initialScrim: Float = 0f,
    val initialAlpha: Float = 1f,
    val initialScale: Float = 1f,
    val offsetDp: Float = 0f,
) {
    val animate: Boolean get() = durationMs > 0
    companion object { val None = ViewTransitionVisual() }
}

internal fun viewTransitionVisual(
    style: ViewTransitionStyle,
    reducedEffects: Boolean,
    backgroundVideoVisible: Boolean,
): ViewTransitionVisual {
    if (reducedEffects) return ViewTransitionVisual.None
    // A visible native decoder plane is never moved, scaled or momentarily exposed
    // through transparent browsing chrome. Fall back to a cheap opaque veil.
    if (backgroundVideoVisible && style != ViewTransitionStyle.OFF) {
        return ViewTransitionVisual(durationMs = 160, initialScrim = .20f)
    }
    return when (style) {
        ViewTransitionStyle.OFF -> ViewTransitionVisual.None
        ViewTransitionStyle.SOFT_FADE -> ViewTransitionVisual(durationMs = 180, initialScrim = .28f)
        ViewTransitionStyle.MICRO_SLIDE -> ViewTransitionVisual(
            durationMs = 210, initialAlpha = .88f, offsetDp = 8f,
        )
        ViewTransitionStyle.CINEMATIC -> ViewTransitionVisual(
            durationMs = 270, initialScrim = .46f, initialScale = .993f,
        )
    }
}

internal fun shouldAnimateViewTransition(
    previousEntryId: Any?,
    targetEntryId: Any?,
    previousFullscreen: Boolean,
    targetFullscreen: Boolean,
    categorySwitchMustStayAtomic: Boolean,
): Boolean = previousEntryId != null && targetEntryId != null &&
    !previousFullscreen && !targetFullscreen && !categorySwitchMustStayAtomic

private data class NavigationStamp(
    val route: String?,
    val entryId: Any?,
    val depth: Int,
    val fullscreen: Boolean,
)

/**
 * Animates an already-switched route's drawing only. The NavHost itself still
 * changes destinations atomically so outgoing pages never own focus/Back at the
 * same time as incoming pages. No layout animation, blur, bitmap copy or extra grid.
 *
 * Animated values are read by the draw/layer modifiers, not by composition.
 * A new route cancels the previous tween; initial composition stays unanimated.
 */
@Composable
internal fun ViewTransitionHost(
    styleKey: String,
    route: String?,
    entryId: Any?,
    backStackDepth: Int,
    fullscreen: Boolean,
    reducedEffects: Boolean,
    backgroundVideoVisible: Boolean,
    skipTransition: (String?, String?) -> Boolean = { _, _ -> false },
    content: @Composable () -> Unit,
) {
    val style = ViewTransitionStyle.fromKey(styleKey)
    val visual = viewTransitionVisual(style, reducedEffects, backgroundVideoVisible)
    val density = LocalDensity.current.density
    val progress = remember { mutableFloatStateOf(1f) }
    var active by remember { mutableStateOf(ViewTransitionVisual.None) }
    var previous by remember { mutableStateOf(NavigationStamp(route, entryId, backStackDepth, fullscreen)) }
    var backward by remember { mutableStateOf(false) }

    if (previous.entryId != entryId || previous.route != route) {
        val eligible = shouldAnimateViewTransition(
            previous.entryId, entryId, previous.fullscreen, fullscreen,
            skipTransition(previous.route, route),
        )
        active = if (eligible) visual else ViewTransitionVisual.None
        backward = backStackDepth < previous.depth
        progress.floatValue = if (active.animate) 0f else 1f
        previous = NavigationStamp(route, entryId, backStackDepth, fullscreen)
    }

    LaunchedEffect(entryId, route, style, reducedEffects, backgroundVideoVisible, fullscreen) {
        if (!active.animate || fullscreen || reducedEffects) {
            progress.floatValue = 1f
        } else {
            Animatable(progress.floatValue).animateTo(
                1f, tween(active.durationMs, easing = FastOutSlowInEasing),
            ) {
                progress.floatValue = value
            }
        }
    }

    // Keep the same Box and NavHost composition slots even in OFF and reduced
    // modes; changing the wrapper tree would re-create the navigation host.
    val layer = if (active.offsetDp != 0f || active.initialScale != 1f || active.initialAlpha != 1f) {
        Modifier.graphicsLayer {
            val remaining = 1f - progress.floatValue
            alpha = 1f - (1f - active.initialAlpha) * remaining
            translationY = active.offsetDp * density * remaining * (if (backward) -1f else 1f)
            scaleX = 1f - (1f - active.initialScale) * remaining
            scaleY = scaleX
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    } else Modifier
    val veil = if (active.initialScrim > 0f) {
        Modifier.drawWithContent {
            drawContent()
            val alpha = active.initialScrim * (1f - progress.floatValue)
            if (alpha > .001f) drawRect(Color.Black.copy(alpha = alpha))
        }
    } else Modifier
    Box(Modifier.fillMaxSize().then(layer).then(veil)) { content() }
}
