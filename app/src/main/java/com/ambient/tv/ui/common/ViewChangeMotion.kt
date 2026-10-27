package com.ambient.tv.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/**
 * How a single already-composed view eases in after a layout change.
 * Sparsam cuts. A full crossfade would keep the previous grid composed for the whole fade.
 */
internal data class ViewChangeMotion(val startAlpha: Float, val durationMillis: Int) {
    val animate: Boolean get() = durationMillis > 0
}

internal fun viewChangeMotion(reducedEffects: Boolean): ViewChangeMotion =
    if (reducedEffects) ViewChangeMotion(1f, 0) else ViewChangeMotion(0.62f, 260)

/**
 * Softens a view-mode change with a short veil over the incoming layout.
 * Only that layout is composed. The first value of [key] is shown at once.
 */
@Composable
internal fun SoftViewChange(
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = viewChangeMotion(LocalPerformanceProfile.current.reducedEffects)
    var veil by remember { mutableFloatStateOf(0f) }
    var current by remember { mutableStateOf(key) }
    if (current != key) {
        current = key
        val next = if (motion.animate) 1f - motion.startAlpha else 0f
        if (veil != next) veil = next
    }
    LaunchedEffect(current) {
        if (!motion.animate || veil == 0f) {
            if (veil != 0f) veil = 0f
            return@LaunchedEffect
        }
        Animatable(veil).animateTo(0f, tween(motion.durationMillis, easing = FastOutSlowInEasing)) {
            veil = value
        }
    }
    Box(
        modifier.drawWithContent {
            drawContent()
            if (veil > 0.01f) drawRect(Color.Black.copy(alpha = veil))
        },
    ) { content() }
}
