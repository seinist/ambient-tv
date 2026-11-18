package com.ambient.tv.ui.mobile

import com.ambient.tv.ui.theme.AccentMotionPaints
import com.ambient.tv.ui.theme.AccentMotionScope
import com.ambient.tv.ui.theme.LocalAccentMotionState
import com.ambient.tv.ui.theme.drawAccentProgressMotion
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.LocalPlayerOsdStow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import com.ambient.tv.ui.player.drawBookmarkTicks
import com.ambient.tv.ui.theme.LocalAccentStyle
import com.ambient.tv.ui.theme.accentBrush
import com.ambient.tv.ui.theme.drawAccentProgress
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.LocalPlayerPlayPauseFocus
import com.ambient.tv.ui.player.LocalPlaybackNavigation
import com.ambient.tv.ui.player.LocalSeekPreviewOverlayState
import com.ambient.tv.ui.player.SEEK_VISUAL_ACK_TIMEOUT_MS
import com.ambient.tv.ui.player.SEEK_VISUAL_ACK_TOLERANCE_MS
import com.ambient.tv.ui.player.seekBarVisualPosition
import com.ambient.tv.ui.player.seekBarFraction
import com.ambient.tv.ui.player.seekBarPlayheadFraction
import com.ambient.tv.ui.player.seekBarThumbScale
import com.ambient.tv.ui.player.seekBarHasDistinctTarget
import com.ambient.tv.ui.performance.LocalPerformanceProfile

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
    interactive: Boolean = true,
    timelineContext: (@Composable (Long) -> Unit)? = null,
) {
    val canSeek = interactive && durationMs > 0L
    val dataAlpha = com.ambient.tv.ui.player.playerTimelineRevealAlpha(durationMs > 0L)
    val accent = com.ambient.tv.ui.theme.LocalPlaybackAccent.current.indicator
    val style = LocalAccentStyle.current
    val progressBrush = remember(accent, style) { accentBrush(accent, style) }
    val progressMotionPaints = remember(accent, style) { AccentMotionPaints(accent, style) }
    // Different ShaderBrush owners: the progress stays anchored as the thumb moves.
    val thumbBrush = remember(accent, style) { accentBrush(accent, style) }
    val navigation = LocalPlaybackNavigation.current
    val osd = LocalPlayerChromeState.current?.osd
    // Remote/media-key direct seeking may show passive feedback without a touch
    // target. Keep its line brief even if the compact OSD remains on screen.
    var remoteSeekFeedback by remember(osd) { mutableStateOf(false) }
    val remoteSeekVersion = if (osd?.seekFeedback == true) osd.interactionVersion else -1
    LaunchedEffect(remoteSeekVersion) {
        remoteSeekFeedback = remoteSeekVersion >= 0
        if (remoteSeekFeedback) {
            kotlinx.coroutines.delay(1_100L)
            remoteSeekFeedback = false
        }
    }
    val stowing = LocalPlayerOsdStow.current?.closing == true
    val playPauseFocus = LocalPlayerPlayPauseFocus.current
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestOnSeekBy by rememberUpdatedState(onSeekBy)
    val latestOnInteraction by rememberUpdatedState(onInteraction)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var dragStartMs by remember { mutableStateOf(positionMs) }
    var awaitingVisualTarget by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(canSeek) {
        if (!canSeek) { dragFraction = null; awaitingVisualTarget = null }
    }
    LaunchedEffect(awaitingVisualTarget) {
        if (awaitingVisualTarget != null) {
            kotlinx.coroutines.delay(SEEK_VISUAL_ACK_TIMEOUT_MS)
            awaitingVisualTarget = null
        }
    }
    LaunchedEffect(positionMs, durationMs, awaitingVisualTarget) {
        val target = awaitingVisualTarget ?: return@LaunchedEffect
        if (durationMs <= 0L || kotlin.math.abs(positionMs - target) <= SEEK_VISUAL_ACK_TOLERANCE_MS) {
            awaitingVisualTarget = null
        }
    }
    val dragTarget = dragFraction?.let { (it * durationMs).toLong() }
    val shownPosition = seekBarVisualPosition(positionMs, durationMs, dragTarget, null, awaitingVisualTarget)
    val fraction = seekBarFraction(shownPosition, durationMs)
    // Only touch scrubbing, an unacknowledged seek or direct remote seek feedback
    // displays the current-position line; idle compact/expanded OSDs stay clean.
    val playheadFraction = seekBarPlayheadFraction(
        positionMs, durationMs,
        seeking = dragFraction != null || awaitingVisualTarget != null || remoteSeekFeedback,
    )
    val showingSeekTarget = seekBarHasDistinctTarget(
        positionMs, shownPosition, dragFraction != null || awaitingVisualTarget != null,
    )
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val thumbAlpha by animateFloatAsState(
        if (dragFraction != null || awaitingVisualTarget != null) 1f else 0f,
        animationSpec = tween(if (reducedEffects) 95 else 145), label = "Touch seek thumb fade",
    )
    val thumbScale by animateFloatAsState(
        seekBarThumbScale(dragFraction != null),
        animationSpec = tween(if (reducedEffects) 95 else 145), label = "Touch seek thumb scale",
    )
    val preview = LocalSeekPreviewOverlayState.current
    val previewTarget = dragTarget
    val previewAvailable = previewTarget?.let { target ->
        navigation?.timeline?.let { it.previewFrameAt(target) != null || it.chapterAt(target) != null }
    } == true
    SideEffect { preview?.update(previewTarget, durationMs, previewAvailable) }
    DisposableEffect(preview) { onDispose { preview?.hide() } }
    val accessibilityLabel = stringResource(
        R.string.player_seekbar_description,
        if (durationMs > 0L) elapsedLabel(shownPosition) else "--:--",
        if (durationMs > 0L) elapsedLabel(durationMs) else "--:--",
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val chapterAlpha by com.ambient.tv.ui.player.seekPreviewYieldAlpha()
        if (timelineContext != null) {
            Column(Modifier.graphicsLayer { alpha = chapterAlpha }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                timelineContext(shownPosition)
            }
        } else {
            com.ambient.tv.ui.player.CurrentChapterLabel(shownPosition,
                Modifier.graphicsLayer { alpha = chapterAlpha })
        }
        AccentMotionScope(active = canSeek && osd?.show != false && osd?.closing != true && !stowing &&
            dragFraction == null) {
            val motionState = LocalAccentMotionState.current
            Box(
                Modifier
                    .fillMaxWidth()
                    // Tall enough to grab, without the empty bands a 48dp box left around the 6dp track.
                    .height(32.dp)
                    .onGloballyPositioned { preview?.trackBoundsInRoot = it.boundsInRoot() }
                    .then(if (playPauseFocus != null) Modifier.focusProperties { down = playPauseFocus } else Modifier)
                    .semantics {
                        contentDescription = accessibilityLabel
                        if (durationMs > 0L) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        if (!canSeek) disabled()
                        if (canSeek) setProgress { target ->
                            com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
                            navigation?.interact()
                            latestOnInteraction()
                            awaitingVisualTarget = (target.coerceIn(0f, 1f) * durationMs).toLong()
                            latestOnSeekBy(handheldSeekDelta(target, latestPositionMs, durationMs))
                            true
                        }
                    }
                    .then(if (canSeek) Modifier.pointerInput(durationMs) {
                        detectTapGestures { point ->
                            com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
                            val target = (point.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            navigation?.interact()
                            latestOnInteraction()
                            awaitingVisualTarget = (target * durationMs).toLong()
                            latestOnSeekBy(handheldSeekDelta(target, latestPositionMs, durationMs))
                        }
                    } else Modifier)
                    .then(if (canSeek) Modifier.pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { point ->
                                com.ambient.tv.ui.player.BackgroundPlaybackSession.userSeek()
                                dragStartMs = latestPositionMs
                                awaitingVisualTarget = null
                                dragFraction = (point.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                navigation?.interact()
                                latestOnInteraction()
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragFraction = (change.position.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                navigation?.interact()
                                latestOnInteraction()
                            },
                            onDragEnd = {
                                dragFraction?.let {
                                    awaitingVisualTarget = (it * durationMs).toLong()
                                    latestOnSeekBy(handheldSeekDelta(it, dragStartMs, durationMs))
                                }
                                dragFraction = null
                                latestOnInteraction()
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    } else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                val bookmarks = com.ambient.tv.ui.player.rememberPlayingBookmarks()
                Canvas(Modifier.fillMaxSize()) {
                    val centerY = size.height / 2f
                    val trackWidth = 6.dp.toPx()
                    inset(vertical = centerY - trackWidth / 2) {
                        drawRoundRect(Color.White.copy(alpha = 0.24f),
                            cornerRadius = CornerRadius(trackWidth / 2, trackWidth / 2))
                    }
                }
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = dataAlpha.value }) {
                    val centerY = size.height / 2f
                    val endX = size.width
                    val trackWidth = 6.dp.toPx()
                    if (durationMs > 0L) {
                        // Scope the material to the visible track, not the taller touch target.
                        inset(vertical = centerY - trackWidth / 2) {
                            drawAccentProgress(progressBrush, fraction)
                            drawAccentProgressMotion(progressMotionPaints, motionState?.value, fraction)
                        }
                        // Restrained chapter emphasis only while actively scrubbing.
                        // The existing tick marks and progress shader remain untouched.
                        if (dragFraction != null) navigation?.timeline?.let { timeline ->
                            val chapter = timeline.chapterAt(shownPosition)
                            if (chapter != null) {
                                val next = timeline.chapters.firstOrNull { it.startMs > chapter.startMs }?.startMs ?: durationMs
                                val end = chapter.endMs?.takeIf { it > chapter.startMs }?.coerceAtMost(next) ?: next
                                val xStart = seekBarFraction(chapter.startMs, durationMs) * endX
                                val xEnd = seekBarFraction(end, durationMs) * endX
                                if (xEnd > xStart) drawRect(
                                    Color.White.copy(alpha = .13f),
                                    topLeft = Offset(xStart, centerY - trackWidth / 2f),
                                    size = androidx.compose.ui.geometry.Size(xEnd - xStart, trackWidth),
                                )
                            }
                        }
                        navigation?.timeline?.chapters?.forEach { chapter ->
                            val x = (chapter.startMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) * endX
                            drawLine(Color.Black.copy(alpha = 0.76f), Offset(x, centerY - trackWidth / 2), Offset(x, centerY + trackWidth / 2), 2.dp.toPx())
                        }
                        drawBookmarkTicks(bookmarks, durationMs, centerY - trackWidth / 2, centerY + trackWidth / 2)
                        if (thumbAlpha > 0f) {
                            val radius = 8.5.dp.toPx() * thumbScale
                            val center = Offset(endX * fraction, centerY)
                            drawCircle(Color.White, radius = radius, center = center, alpha = thumbAlpha)
                            drawCircle(brush = thumbBrush, radius = radius - 3.dp.toPx() * thumbScale,
                                center = center, alpha = thumbAlpha)
                        }
                        // Draw last while seeking: the current position stays separate
                        // from the touch target/thumb and never appears when idle.
                        if (playheadFraction != null) {
                            val insetX = 1.5.dp.toPx()
                            val x = (playheadFraction * endX).coerceIn(insetX, (endX - insetX).coerceAtLeast(insetX))
                            drawLine(
                                Color.White.copy(alpha = .84f),
                                Offset(x, centerY - 6.dp.toPx()),
                                Offset(x, centerY + 6.dp.toPx()),
                                2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Same readout as the TV player: position / duration, remaining time and end time.
            val remaining = (durationMs - shownPosition).coerceAtLeast(0L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.ambient.tv.ui.player.PlayerOsdNavigationIndicator(paused)
                com.ambient.tv.ui.player.PlayerTimelineTimeText(
                    ready = durationMs > 0L,
                    text = "${elapsedLabel(if (showingSeekTarget) positionMs else shownPosition)} / ${elapsedLabel(durationMs)}",
                    color = Color.White.copy(alpha = 0.82f),
                    placeholder = "--:-- / --:--",
                )
            }
            val endsAt = if (showEndTime && durationMs > 0L) {
                java.time.LocalTime.now().plusSeconds(remaining / 1000L).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            } else null
            com.ambient.tv.ui.player.PlayerTimelineTimeText(
                ready = durationMs > 0L,
                text = if (showingSeekTarget) {
                    stringResource(R.string.player_seek_target, elapsedLabel(shownPosition))
                } else endsAt?.let { "−${elapsedLabel(remaining)} · ${stringResource(R.string.player_ends_at, it)}" } ?: "−${elapsedLabel(remaining)}",
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

internal fun handheldSeekDelta(fraction: Float, currentMs: Long, durationMs: Long): Long =
    (fraction.coerceIn(0f, 1f).toDouble() * durationMs.coerceAtLeast(0L)).toLong() - currentMs.coerceAtLeast(0L)

private fun elapsedLabel(ms: Long): String = com.ambient.tv.ui.common.formatPlaybackClock(ms)
