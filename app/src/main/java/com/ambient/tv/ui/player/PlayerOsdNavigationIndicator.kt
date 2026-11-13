package com.ambient.tv.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.ambient.tv.R
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.mobile.LocalHandheldUi

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
 * Small circular badge before the TV timeline readout. Handhelds always retain the
 * animated pause-only indicator, which slides the clock without showing remote navigation
 * modes. The TV setting still controls the TV badge as before.
 */
@Composable
internal fun PlayerOsdNavigationIndicator(paused: Boolean) {
    // Touch navigation has no remote-control mode to indicate. Keep pause feedback and
    // its animated, width-changing clock spacing even when the TV badge is enabled.
    if (LocalHandheldUi.current) {
        PlayerPausedIndicator(paused, trailingSpacing = 6.dp)
        return
    }
    val context = LocalContext.current
    // Preview and Robolectric seekbar tests can host the shared component without the
    // application's Hilt graph. In that case the portable default is to show the icon.
    val preferences = remember(context) { runCatching { videoImagePreferences(context) }.getOrNull() }
    if (preferences?.playerOsdModeIconVisible == false) {
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
                val description = when (shown) {
                    PlayerOsdNavigationMode.PAUSE -> R.string.player_paused
                    PlayerOsdNavigationMode.CHAPTERS -> R.string.player_osd_navigation_chapters
                    PlayerOsdNavigationMode.BUTTONS -> R.string.player_osd_navigation_buttons
                    PlayerOsdNavigationMode.SEEK -> R.string.player_osd_navigation_seek
                }
                when (shown) {
                    PlayerOsdNavigationMode.PAUSE, PlayerOsdNavigationMode.CHAPTERS -> Icon(
                        imageVector = if (shown == PlayerOsdNavigationMode.PAUSE) Icons.Filled.Pause
                            else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(description),
                        modifier = Modifier.size(15.dp),
                        tint = Color.White.copy(alpha = 0.82f),
                    )
                    PlayerOsdNavigationMode.BUTTONS, PlayerOsdNavigationMode.SEEK ->
                        PlayerOsdCustomGlyph(shown, stringResource(description))
                }
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * Two deliberately distinct 15dp navigation glyphs:
 * - Controls: a remote-style four-way D-pad with a centre button.
 * - Seeking: horizontal skip chevrons over a track with a vertical playhead.
 *
 * Drawn directly instead of using SwapHoriz (which implies swapping) or ViewWeek (which looks
 * like columns). Both use the same stroke/tint and 20dp circular badge as the pause symbol.
 */
@Composable
private fun PlayerOsdCustomGlyph(mode: PlayerOsdNavigationMode, label: String) {
    Canvas(Modifier.size(15.dp).semantics { contentDescription = label }) {
        val unit = size.minDimension / 24f
        val tint = Color.White.copy(alpha = 0.82f)
        fun pos(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (mode) {
            PlayerOsdNavigationMode.BUTTONS -> {
                val arrows = Path().apply {
                    // Up and down.
                    moveTo(12f * unit, 2.6f * unit)
                    lineTo(8.5f * unit, 8.5f * unit)
                    lineTo(15.5f * unit, 8.5f * unit)
                    close()
                    moveTo(12f * unit, 21.4f * unit)
                    lineTo(8.5f * unit, 15.5f * unit)
                    lineTo(15.5f * unit, 15.5f * unit)
                    close()
                    // Left and right.
                    moveTo(2.6f * unit, 12f * unit)
                    lineTo(8.5f * unit, 8.5f * unit)
                    lineTo(8.5f * unit, 15.5f * unit)
                    close()
                    moveTo(21.4f * unit, 12f * unit)
                    lineTo(15.5f * unit, 8.5f * unit)
                    lineTo(15.5f * unit, 15.5f * unit)
                    close()
                }
                drawPath(arrows, tint)
                drawCircle(tint, radius = 2f * unit, center = pos(12f, 12f))
            }
            PlayerOsdNavigationMode.SEEK -> {
                val stroke = 2.2f * unit
                // Opposing skip chevrons make left/right seeking immediately recognizable.
                drawLine(tint, pos(10.5f, 5f), pos(6.5f, 9f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(6.5f, 9f), pos(10.5f, 13f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(13.5f, 5f), pos(17.5f, 9f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(17.5f, 9f), pos(13.5f, 13f), stroke, cap = StrokeCap.Round)
                // Timeline and current position below the chevrons.
                drawLine(tint, pos(3.7f, 18f), pos(20.3f, 18f), 1.7f * unit, cap = StrokeCap.Round)
                drawLine(tint, pos(12f, 15f), pos(12f, 21f), 2.3f * unit, cap = StrokeCap.Round)
                drawCircle(tint, radius = 1.55f * unit, center = pos(12f, 18f))
            }
            else -> Unit
        }
    }
}
