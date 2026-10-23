package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val fraction = progress.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(1.dp)
    Box(
        modifier
            .height(PlaybackStatusBarHeight)
            .clip(shape)
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(LocalPlaybackAccent.current.indicator),
        )
    }
}
