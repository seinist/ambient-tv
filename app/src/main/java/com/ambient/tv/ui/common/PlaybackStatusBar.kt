package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import com.ambient.tv.ui.theme.LocalAccentStyle
import com.ambient.tv.ui.theme.accentBrush
import com.ambient.tv.ui.theme.drawAccentProgress
import com.ambient.tv.ui.theme.drawAccentProgressMotion
import com.ambient.tv.ui.theme.AccentMotionPaints
import com.ambient.tv.ui.theme.LocalAccentMotionState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.theme.LocalPlaybackAccent

/** Thin Plex-style watched-position strip shared by browse, detail and compact playback UI. */
internal val PlaybackStatusBarHeight = 4.dp

@Composable
fun PlaybackStatusBar(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.Black.copy(alpha = 0.58f),
) {
    val fraction = rememberUpdatedState(if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f)
    val accent = LocalPlaybackAccent.current.indicator
    val style = LocalAccentStyle.current
    val motion = LocalAccentMotionState.current
    val shape = RoundedCornerShape(1.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(PlaybackStatusBarHeight)
            .clip(shape)
            .drawWithCache {
                val progressBrush = accentBrush(accent, style)
                val motionPaints = if (motion != null) AccentMotionPaints(accent, style) else null
                onDrawBehind {
                    drawRect(trackColor)
                    drawAccentProgress(progressBrush, fraction.value,
                        fromRight = layoutDirection == LayoutDirection.Rtl, radius = 1.dp.toPx())
                    if (motionPaints != null) drawAccentProgressMotion(motionPaints, motion?.value, fraction.value,
                        fromRight = layoutDirection == LayoutDirection.Rtl, radius = 1.dp.toPx())
                }
            },
    )
}
