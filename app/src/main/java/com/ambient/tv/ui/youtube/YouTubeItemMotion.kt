package com.ambient.tv.ui.youtube

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.ambient.tv.ui.performance.LocalPerformanceProfile

private val itemPlacement = tween<IntOffset>(240, easing = FastOutSlowInEasing)
private val reducedItemPlacement = tween<IntOffset>(180, easing = FastOutSlowInEasing)
private val itemAppearance = tween<Float>(180)
private val reducedItemAppearance = tween<Float>(120)
private val itemDisappearance = tween<Float>(120)

/** Keep the whole keyed card together when feeds insert, remove or reorder videos. */
@Composable
internal fun LazyItemScope.youtubeItemMotionModifier(): Modifier {
    val reduced = LocalPerformanceProfile.current.reducedEffects
    return Modifier.animateItem(
        fadeInSpec = if (reduced) reducedItemAppearance else itemAppearance,
        placementSpec = if (reduced) reducedItemPlacement else itemPlacement,
        fadeOutSpec = if (reduced) null else itemDisappearance,
    )
}

/** Grids use the same transition as rows and lists, including the lightweight UI profile. */
@Composable
internal fun LazyGridItemScope.youtubeItemMotionModifier(): Modifier {
    val reduced = LocalPerformanceProfile.current.reducedEffects
    return Modifier.animateItem(
        fadeInSpec = if (reduced) reducedItemAppearance else itemAppearance,
        placementSpec = if (reduced) reducedItemPlacement else itemPlacement,
        fadeOutSpec = if (reduced) null else itemDisappearance,
    )
}
