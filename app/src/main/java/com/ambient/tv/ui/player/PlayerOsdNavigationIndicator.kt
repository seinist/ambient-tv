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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
internal enum class PlayerOsdNavigationMode { PAUSE, CHAPTERS, DIRECT_SEEK, TIMELINE, BUTTONS }

internal fun playerOsdNavigationMode(
    paused: Boolean,
    chaptersActive: Boolean,
    directSeekFeedback: Boolean,
    controlsExpanded: Boolean,
    seekFocused: Boolean,
    buttonFocused: Boolean,
): PlayerOsdNavigationMode = when {
    paused -> PlayerOsdNavigationMode.PAUSE
    chaptersActive -> PlayerOsdNavigationMode.CHAPTERS
    // The passive feedback bar looks like the interactive timeline, but Down still jumps back.
    // Do not let a stale timeline focus override the actual key-handling state.
    directSeekFeedback -> PlayerOsdNavigationMode.DIRECT_SEEK
    seekFocused -> PlayerOsdNavigationMode.TIMELINE
    controlsExpanded || buttonFocused -> PlayerOsdNavigationMode.BUTTONS
    else -> PlayerOsdNavigationMode.TIMELINE
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
        directSeekFeedback = osd?.seekFeedback == true,
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
                    PlayerOsdNavigationMode.DIRECT_SEEK -> R.string.player_osd_navigation_seek
                    PlayerOsdNavigationMode.TIMELINE -> R.string.player_osd_navigation_timeline
                    PlayerOsdNavigationMode.BUTTONS -> R.string.player_osd_navigation_buttons
                }
                when (shown) {
                    PlayerOsdNavigationMode.PAUSE, PlayerOsdNavigationMode.CHAPTERS -> Icon(
                        imageVector = if (shown == PlayerOsdNavigationMode.PAUSE) Icons.Filled.Pause
                            else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(description),
                        modifier = Modifier.size(15.dp),
                        tint = Color.White.copy(alpha = 0.82f),
                    )
                    PlayerOsdNavigationMode.DIRECT_SEEK, PlayerOsdNavigationMode.TIMELINE,
                    PlayerOsdNavigationMode.BUTTONS ->
                        PlayerOsdCustomGlyph(shown, stringResource(description))
                }
            }
        }
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * Three deliberately distinct 15dp navigation glyphs:
 * - Direct seek: four-way arrows (Up/Down jump chapters or time).
 * - Timeline focus: left/right seeking with a downward disclosure for the action buttons.
 * - Buttons: a row of three selectable controls with the centre selected.
 *
 * These follow the input state, not the identical compact OSD geometry. All use the same
 * 20dp circular slot and stroke/tint as the pause and chapters icons.
 */
@Composable
private fun PlayerOsdCustomGlyph(mode: PlayerOsdNavigationMode, label: String) {
    Canvas(Modifier.size(15.dp).semantics { contentDescription = label }) {
        val unit = size.minDimension / 24f
        val tint = Color.White.copy(alpha = 0.82f)
        fun pos(x: Float, y: Float) = Offset(x * unit, y * unit)
        when (mode) {
            PlayerOsdNavigationMode.DIRECT_SEEK -> {
                // Four-way jumps, including Down for previous chapter / backward jump.
                val arrows = Path().apply {
                    moveTo(12f * unit, 2.6f * unit)
                    lineTo(8.5f * unit, 8.5f * unit)
                    lineTo(15.5f * unit, 8.5f * unit)
                    close()
                    moveTo(12f * unit, 21.4f * unit)
                    lineTo(8.5f * unit, 15.5f * unit)
                    lineTo(15.5f * unit, 15.5f * unit)
                    close()
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
            PlayerOsdNavigationMode.TIMELINE -> {
                // Left/right seek, while Down opens the buttons instead of jumping backward.
                val stroke = 2.2f * unit
                drawLine(tint, pos(10.5f, 3.3f), pos(6f, 8f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(6f, 8f), pos(10.5f, 12.7f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(13.5f, 3.3f), pos(18f, 8f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(18f, 8f), pos(13.5f, 12.7f), stroke, cap = StrokeCap.Round)
                drawLine(tint, pos(4f, 15.5f), pos(20f, 15.5f), 1.7f * unit, cap = StrokeCap.Round)
                drawLine(tint, pos(9.4f, 18f), pos(12f, 20.9f), 1.8f * unit, cap = StrokeCap.Round)
                drawLine(tint, pos(12f, 20.9f), pos(14.6f, 18f), 1.8f * unit, cap = StrokeCap.Round)
            }
            PlayerOsdNavigationMode.BUTTONS -> {
                // Selected middle key in a three-button control strip.
                val radius = CornerRadius(2f * unit)
                drawRoundRect(
                    color = tint.copy(alpha = 0.65f),
                    topLeft = pos(2f, 9f), size = Size(5f * unit, 7f * unit),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = pos(8.3f, 5.5f), size = Size(7.4f * unit, 14f * unit),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = tint.copy(alpha = 0.65f),
                    topLeft = pos(17f, 9f), size = Size(5f * unit, 7f * unit),
                    cornerRadius = radius,
                )
                drawCircle(Color.Black.copy(alpha = 0.75f), radius = 1.5f * unit, center = pos(12f, 12.5f))
            }
            else -> Unit
        }
    }
}
