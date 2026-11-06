package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/** Based on playback intent, not isPlaying (which is also false during buffering). */
internal fun shouldShowPlayerPaused(
    playRequested: Boolean,
    active: Boolean = true,
    changingItem: Boolean = false,
    ended: Boolean = false,
): Boolean = active && !playRequested && !changingItem && !ended

/** A short visible transition also in Sparsam; no shadow or blur. */
internal fun pausedIndicatorMotionMs(reducedEffects: Boolean): Int =
    if (reducedEffects) 120 else 180

/**
 * The visible OSD owns this indicator, never the video surface. Its spacing is
 * inside the animated width so the adjacent time readout moves without jumping.
 */
@Composable
internal fun PlayerPausedIndicator(
    paused: Boolean,
    showLabel: Boolean = false,
    leadingSpacing: Dp = 0.dp,
    trailingSpacing: Dp = 0.dp,
) {
    val duration = pausedIndicatorMotionMs(LocalPerformanceProfile.current.reducedEffects)
    AnimatedVisibility(
        visible = paused,
        enter = expandHorizontally(
            expandFrom = Alignment.Start,
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
        ) + fadeIn(animationSpec = tween(durationMillis = duration)),
        exit = shrinkHorizontally(
            shrinkTowards = Alignment.Start,
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
        ) + fadeOut(animationSpec = tween(durationMillis = duration)),
    ) {
        val label = stringResource(R.string.player_paused)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingSpacing > 0.dp) Spacer(Modifier.width(leadingSpacing))
            Icon(
                imageVector = Icons.Filled.Pause,
                contentDescription = if (showLabel) null else label,
                modifier = Modifier.size(15.dp),
                tint = Color.White.copy(alpha = 0.82f),
            )
            if (showLabel) {
                Spacer(Modifier.width(4.dp))
                Text(label, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, maxLines = 1)
            }
            if (trailingSpacing > 0.dp) Spacer(Modifier.width(trailingSpacing))
        }
    }
}
