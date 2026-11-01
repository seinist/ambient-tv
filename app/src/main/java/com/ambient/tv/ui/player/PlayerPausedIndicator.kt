package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.ambient.tv.R

/** Based on playback intent, not isPlaying (which is also false during buffering). */
internal fun shouldShowPlayerPaused(
    playRequested: Boolean,
    active: Boolean = true,
    changingItem: Boolean = false,
    ended: Boolean = false,
): Boolean = active && !playRequested && !changingItem && !ended

/** Only the currently visible OSD draws this; no permanent video overlay. */
@Composable
internal fun PlayerPausedIndicator(paused: Boolean, showLabel: Boolean = false) {
    if (!paused) return
    val label = stringResource(R.string.player_paused)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Pause,
            contentDescription = if (showLabel) null else label,
            modifier = Modifier.size(15.dp),
            tint = Color.White.copy(alpha = 0.82f),
        )
        if (showLabel) {
            Text(label, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, maxLines = 1)
        }
    }
}
