package com.ambient.tv.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.ambient.tv.R
import com.ambient.tv.ui.performance.LocalPerformanceProfile

/** One fixed slot in the timeline readout. Pause wins over every navigation mode. */
internal enum class PlayerOsdNavigationMode { PAUSE, CHAPTERS, BUTTONS, SEEK }

internal fun playerOsdNavigationMode(
    paused: Boolean,
    chaptersActive: Boolean,
    controlsExpanded: Boolean,
    seekFocused: Boolean,
    buttonFocused: Boolean,
): PlayerOsdNavigationMode = when {
    paused -> PlayerOsdNavigationMode.PAUSE
    chaptersActive -> PlayerOsdNavigationMode.CHAPTERS
    seekFocused -> PlayerOsdNavigationMode.SEEK
    controlsExpanded || buttonFocused -> PlayerOsdNavigationMode.BUTTONS
    else -> PlayerOsdNavigationMode.SEEK
}

/**
 * Small circular badge before position/duration, shared by all TV/phone seekbars.
 * With the setting off, retain the original animated pause-only indicator.
 * Its 20dp footprint never changes during a mode switch, so time cannot jump.
 */
@Composable
internal fun PlayerOsdNavigationIndicator(paused: Boolean) {
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    if (!preferences.playerOsdModeIconVisible) {
        PlayerPausedIndicator(paused, trailingSpacing = 6.dp)
        return
    }

    val osd = LocalPlayerChromeState.current?.osd
    val controlFocus = LocalPlayerControlFocus.current
    val chapters = LocalPlaybackNavigation.current
    val mode = playerOsdNavigationMode(
        paused = paused,
        chaptersActive = chapters?.chaptersOpen == true || chapters?.chapterNotice != null,
        controlsExpanded = osd?.expanded == true,
        seekFocused = osd?.seekBarFocused == true || controlFocus?.seekFocused == true,
        buttonFocused = controlFocus?.let { it.hasFocus && !it.seekFocused && !it.compactFocused } == true,
    )
    val duration = pausedIndicatorMotionMs(LocalPerformanceProfile.current.reducedEffects)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(20.dp).background(Color.White.copy(alpha = 0.09f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = mode,
                animationSpec = tween(durationMillis = duration),
                label = "OSD navigation icon",
            ) { shown ->
                val vector = when (shown) {
                    PlayerOsdNavigationMode.PAUSE -> Icons.Filled.Pause
                    PlayerOsdNavigationMode.CHAPTERS -> Icons.AutoMirrored.Filled.ViewList
                    PlayerOsdNavigationMode.BUTTONS -> Icons.Filled.ViewWeek
                    PlayerOsdNavigationMode.SEEK -> Icons.Filled.SwapHoriz
                }
                val description = when (shown) {
                    PlayerOsdNavigationMode.PAUSE -> R.string.player_paused
                    PlayerOsdNavigationMode.CHAPTERS -> R.string.player_osd_navigation_chapters
                    PlayerOsdNavigationMode.BUTTONS -> R.string.player_osd_navigation_buttons
                    PlayerOsdNavigationMode.SEEK -> R.string.player_osd_navigation_seek
                }
                Icon(
                    imageVector = vector,
                    contentDescription = stringResource(description),
                    modifier = Modifier.size(15.dp),
                    tint = Color.White.copy(alpha = 0.82f),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}
