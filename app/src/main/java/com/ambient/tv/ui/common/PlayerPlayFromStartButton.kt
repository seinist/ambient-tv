package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.rememberPlayerOsdController

/** Optional transport action, placed immediately after play/pause by each video OSD. */
@Composable
internal fun PlayerPlayFromStartButton(onClick: () -> Unit, enabled: Boolean = true) {
    val osd = rememberPlayerOsdController()
    PlayerIconButton(
        icon = OsdButton.PLAY_FROM_START.icon,
        label = stringResource(OsdButton.PLAY_FROM_START.label),
        onClick = { osd.interact(); onClick() },
        enabled = enabled,
        osd = OsdButton.PLAY_FROM_START,
    )
}
