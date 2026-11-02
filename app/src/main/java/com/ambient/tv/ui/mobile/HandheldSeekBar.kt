package com.ambient.tv.ui.mobile

import androidx.compose.foundation.Canvas
import com.ambient.tv.ui.player.drawBookmarkTicks
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.R
import com.ambient.tv.ui.common.LocalPlayerPlayPauseFocus
import com.ambient.tv.ui.player.LocalPlaybackNavigation
import com.ambient.tv.ui.player.LocalSeekPreviewOverlayState

/** Plex-styled touch scrubber. Its track matches the TV player inside a compact 32dp touch strip. */
@Composable
fun HandheldSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekBy: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    showEndTime: Boolean = true,
    paused: Boolean = false,
) {
    val accent = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator
    val navigation = LocalPlaybackNavigation.current
    val playPauseFocus = LocalPlayerPlayPauseFocus.current
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestOnSeekBy by rememberUpdatedState(onSeekBy)
    val latestOnInteraction by rememberUpdatedState(onInteraction)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var dragStartMs by remember { mutableStateOf(positionMs) }
    val fraction = dragFraction ?: (positionMs.toDouble() / durationMs.coerceAtLeast(1L))
        .toFloat().coerceIn(0f, 1f)
    val shownPosition = if (dragFraction != null) (fraction * durationMs).toLong() else positionMs
    val preview = LocalSeekPreviewOverlayState.current
    val previewTarget = dragFraction?.let { shownPosition }
    val previewAvailable = previewTarget?.let { target ->
        navigation?.timeline?.let { it.previewFrameAt(target) != null || it.chapterAt(target) != null }
    } == true
    SideEffect { preview?.update(previewTarget, durationMs, previewAvailable) }
    DisposableEffect(preview) { onDispose { preview?.hide() } }
    val accessibilityLabel = stringResource(R.string.player_seekbar_description, elapsedLabel(shownPosition), elapsedLabel(durationMs))

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val chapterAlpha by com.ambient.tv.ui.player.seekPreviewYieldAlpha()
        com.ambient.tv.ui.player.CurrentChapterLabel(shownPosition,
            Modifier.graphicsLayer { alpha = chapterAlpha })
        Box(
            Modifier
                .fillMaxWidth()
                // Tall enough to grab, without the empty bands a 48dp box left around the 6dp track.
                .height(32.dp)
                .onGloballyPositioned { preview?.trackBoundsInRoot = it.boundsInRoot() }
                .then(if (playPauseFocus != null) Modifier.focusProperties { down = playPauseFocus } else Modifier)
                .semantics {
                    contentDescription = accessibilityLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        if (durationMs > 0L) {
                            com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
                            navigation?.interact()
                            latestOnInteraction()
                            latestOnSeekBy(handheldSeekDelta(target, latestPositionMs, durationMs))
                            true
                        } else false
                    }
                }
                .pointerInput(durationMs) {
                    detectTapGestures { point ->
                        if (durationMs > 0L) {
                            com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
                            val target = (point.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            navigation?.interact()
                            latestOnInteraction()
                            latestOnSeekBy(handheldSeekDelta(target, latestPositionMs, durationMs))
                        }
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { point ->
                            com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
                            dragStartMs = latestPositionMs
                            dragFraction = (point.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            navigation?.interact()
                            latestOnInteraction()
                        },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            navigation?.interact()
                            latestOnInteraction()
                        },
                        onDragEnd = {
                            dragFraction?.let { latestOnSeekBy(handheldSeekDelta(it, dragStartMs, durationMs)) }
                            dragFraction = null
                            latestOnInteraction()
                        },
                        onDragCancel = { dragFraction = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val bookmarks = com.ambient.tv.ui.player.rememberPlayingBookmarks()
            Canvas(Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val endX = size.width
                val trackWidth = 6.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.24f), Offset(0f, centerY), Offset(endX, centerY), trackWidth, StrokeCap.Round)
                if (durationMs > 0L) {
                    drawLine(accent, Offset(0f, centerY), Offset(endX * fraction, centerY), trackWidth, StrokeCap.Round)
                    navigation?.timeline?.chapters?.forEach { chapter ->
                        val x = (chapter.startMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) * endX
                        drawLine(Color.Black.copy(alpha = 0.76f), Offset(x, centerY - trackWidth / 2), Offset(x, centerY + trackWidth / 2), 2.dp.toPx())
                    }
                    drawBookmarkTicks(bookmarks, durationMs, centerY - trackWidth / 2, centerY + trackWidth / 2)
                    if (dragFraction != null) {
                        val radius = 8.5.dp.toPx()
                        drawCircle(Color.White, radius = radius, center = Offset(endX * fraction, centerY))
                        drawCircle(accent, radius = radius - 3.dp.toPx(), center = Offset(endX * fraction, centerY))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Same readout as the TV player: position / duration, remaining time and end time.
            val remaining = (durationMs - shownPosition).coerceAtLeast(0L)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.ambient.tv.ui.player.PlayerPausedIndicator(paused)
                Text(
                    if (durationMs > 0L) "${elapsedLabel(shownPosition)} / ${elapsedLabel(durationMs)}" else elapsedLabel(shownPosition),
                    color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp,
                )
            }
            val endsAt = if (showEndTime && durationMs > 0L) {
                java.time.LocalTime.now().plusSeconds(remaining / 1000L).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            } else null
            Text(
                endsAt?.let { "−${elapsedLabel(remaining)} · ${stringResource(R.string.player_ends_at, it)}" } ?: "−${elapsedLabel(remaining)}",
                color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp,
            )
        }
    }
}

internal fun handheldSeekDelta(fraction: Float, currentMs: Long, durationMs: Long): Long =
    (fraction.coerceIn(0f, 1f).toDouble() * durationMs.coerceAtLeast(0L)).toLong() - currentMs.coerceAtLeast(0L)

private fun elapsedLabel(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)
