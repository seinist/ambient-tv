@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat
import java.util.Locale

internal fun readFrameDrops(handle: PlaybackHandle?): FrameDropReading {
    val status = handle?.status() ?: return FrameDropReading()
    val playing = status.playing && !status.ended && status.error == null
    val reading = when (handle) {
        is MpvPlaybackController -> FrameDropReading(
            dropped = handle.frameDropCount(),
            positionMs = status.positionMs,
            playing = playing && handle.frameDropSampleReady(),
            fps = handle.targetVideoFps(),
            estimated = true,
            speed = handle.currentSpeed().toDouble(),
        )
        is VlcPlaybackController -> handle.runtimeStats()?.let {
            FrameDropReading(
                dropped = it.lostPictures?.toLong(), rendered = it.displayedPictures?.toLong(),
                positionMs = status.positionMs, playing = playing && handle.frameDropSampleReady(),
                fps = handle.targetVideoFps(it.videoFps), speed = handle.currentSpeed().toDouble(),
            )
        } ?: FrameDropReading()
        is Media3PlaybackHandle -> handle.player.videoDecoderCounters?.let {
            it.ensureUpdated()
            FrameDropReading(
                dropped = it.droppedBufferCount.toLong(), rendered = it.renderedOutputBufferCount.toLong(),
                positionMs = status.positionMs, playing = playing,
                fps = handle.player.videoFormat?.frameRate?.toDouble(),
                speed = handle.player.playbackParameters.speed.toDouble(),
            )
        } ?: FrameDropReading()
        else -> FrameDropReading()
    }
    val generation = when (handle) {
        is Media3PlaybackHandle -> handle.frameDropSampleGeneration()
        is VlcPlaybackController -> handle.frameDropSampleGeneration()
        else -> handle.reopenGeneration()
    }
    return reading.copy(realtimeMs = android.os.SystemClock.elapsedRealtime(), generation = generation)
}

private val frameDropColorStops = listOf(
    0.0 to Color(0xFF66BB6A),
    0.5 to Color(0xFFA5D66B),
    2.0 to Color(0xFFFFB74D),
    FRAME_DROP_WATCHDOG_PERCENT to Color(0xFFEF5350),
)

/** Green while (almost) every frame is shown, through amber to red at the watchdog threshold. */
internal fun frameDropBarColor(dropPercent: Double?): Color {
    val value = dropPercent ?: return Color(0xFF90A4AE)
    frameDropColorStops.zipWithNext().forEach { (from, to) ->
        if (value <= to.first) return lerp(from.second, to.second, ((value - from.first) / (to.first - from.first)).coerceIn(0.0, 1.0).toFloat())
    }
    return frameDropColorStops.last().second
}

/**
 * Window overlay shown in video panels or for the playback when enabled in OSD & Info.
 * Fades in and out and never takes focus or handles Back.
 * Shows a smoothed FPS deviation when the intended output rate is known. Otherwise real
 * renderer counters still provide output FPS and the observed proportion of dropped frames.
 * Hosted by [PlayerPanelVisibility] so it outlives the panel long enough to fade out.
 */
@Composable
internal fun PlaybackFrameDropOverlay(visible: Boolean) {
    val playback by BackgroundPlaybackSession.snapshot.collectAsState()
    val handle = BackgroundPlaybackSession.activeHandle()
    val session = playbackProfileAudio(handle)
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = (visible || session?.showFrameDropOverlay == true) &&
        playback.metadata?.hasVideo == true && session?.audioOnly != true &&
        (handle is MpvPlaybackController || handle is VlcPlaybackController || handle is Media3PlaybackHandle)
    if (!visibility.currentState && !visibility.targetState) return
    var targetFps by remember(handle) { mutableStateOf<Double?>(null) }
    var smoothed by remember(handle) { mutableStateOf<FrameDropSample?>(null) }
    var historyPoints by remember(handle) { mutableStateOf<List<FrameDropHistoryPoint>>(emptyList()) }
    LaunchedEffect(handle) {
        val history = FrameDropHistory()
        while (isActive) {
            val current = readFrameDrops(handle)
            smoothed = smoothFrameDrop(smoothed, history.record(current))
            historyPoints = history.points
            targetFps = current.targetFps
            delay(1_000)
        }
    }
    val fill by animateFloatAsState(frameDropBarFill(smoothed?.deficitPercent), tween(900, easing = FastOutSlowInEasing), label = "frameDropFill")
    val color by animateColorAsState(frameDropBarColor(smoothed?.deficitPercent), tween(900), label = "frameDropColor")
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val label = if (targetFps == null && handle !is MpvPlaybackController) {
        stringResource(
            R.string.player_frame_drop_measured_fps,
            formatFrameOutputFps(smoothed?.actualFps, locale),
            formatFramePercent(smoothed?.counterDropPercent, locale),
        )
    } else {
        val deviation = (if (smoothed?.estimated == true) "≈" else "") + formatFrameFpsDeviation(smoothed?.deviationFps, locale)
        stringResource(R.string.player_frame_drop_fps_deviation, deviation, formatFrameTargetFps(targetFps, locale))
    }
    val inset = with(LocalDensity.current) { IntOffset(24.dp.roundToPx(), 28.dp.roundToPx()) }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val showHistory = session?.showFrameDropHistory == true
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val graphWidth = minOf(320.dp, (windowWidth - 48.dp).coerceAtLeast(200.dp))
    // The FPS overlay sits in the same corner; stack below it instead of drawing on top.
    val fpsBounds = PlaybackStatsOverlayLayout.fpsBounds
    val position = remember(inset, gap, fpsBounds) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
            stackedStatsPopupOffset(inset, popupContentSize, windowSize, fpsBounds, gap)
    } }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        AnimatedVisibility(visibility, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Column(
                modifier = Modifier
                    .then(if (showHistory) Modifier.width(graphWidth) else Modifier)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(width = 96.dp, height = 4.dp)) {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = radius)
                        drawRoundRect(color, size = Size(size.width * fill, size.height), cornerRadius = radius)
                        // Watchdog threshold sits at the middle of the scale.
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width / 2, -1.dp.toPx()), Offset(size.width / 2, size.height + 1.dp.toPx()), strokeWidth = 1.dp.toPx())
                    }
                    Text(
                        text = label,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (showHistory) PlaybackFrameDropHistory(historyPoints, locale)
            }
        }
    }
}

/** Fixed, labelled percentage scale; gaps and clipped peaks stay visible as such. */
@Composable
private fun PlaybackFrameDropHistory(points: List<FrameDropHistoryPoint>, locale: Locale) {
    val latest = points.lastOrNull()
    val estimated = latest?.estimated == true
    val value = (if (estimated && latest.percent != null) "≈" else "") + formatFramePercent(latest?.percent, locale)
    val axisColor = Color.White.copy(alpha = 0.6f)
    val axisFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val axisWidth = 42.dp
    val plotHeight = 76.dp
    val seconds = (FRAME_DROP_HISTORY_WINDOW_MS / 1_000).toInt()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.player_frame_drop_history_title, seconds), color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    }
    if (estimated) Text(stringResource(R.string.player_frame_drop_history_estimated), color = axisColor,
        fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(axisWidth).height(plotHeight), verticalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.player_frame_drop_history_ceiling, FRAME_DROP_HISTORY_MAX_PERCENT.toInt()),
                color = axisColor, fontSize = 10.sp, lineHeight = 12.sp)
            Text("${axisFormat.format(FRAME_DROP_HISTORY_MAX_PERCENT / 2)} %",
                color = axisColor, fontSize = 10.sp, lineHeight = 12.sp)
            Text("${axisFormat.format(0)} %",
                color = axisColor, fontSize = 10.sp, lineHeight = 12.sp)
        }
        Canvas(Modifier.weight(1f).height(plotHeight)) {
            val top = 6.dp.toPx()
            val bottom = size.height - top
            val plotHeightPx = bottom - top
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = top + plotHeightPx * fraction
                drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y), Offset(size.width, y), 0.75.dp.toPx())
            }
            val now = points.lastOrNull()?.realtimeMs ?: return@Canvas
            val start = now - FRAME_DROP_HISTORY_WINDOW_MS
            val dashed = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            var previous: Offset? = null
            var previousEstimated = false
            for (point in points) {
                val percent = point.percent
                if (percent == null) {
                    previous = null
                    continue
                }
                val x = ((point.realtimeMs - start).toFloat() / FRAME_DROP_HISTORY_WINDOW_MS).coerceIn(0f, 1f) * size.width
                val y = bottom - (percent / FRAME_DROP_HISTORY_MAX_PERCENT).coerceIn(0.0, 1.0).toFloat() * plotHeightPx
                val position = Offset(x, y)
                val pointColor = frameDropBarColor(percent)
                if (previous != null && previousEstimated == point.estimated) {
                    drawLine(pointColor, previous, position, 1.75.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (point.estimated) dashed else null)
                } else drawCircle(pointColor, 1.5.dp.toPx(), position)
                // The true percentage remains in the buffer/heading; only drawing is capped.
                if (percent > FRAME_DROP_HISTORY_MAX_PERCENT) {
                    val marker = 3.dp.toPx()
                    val center = x.coerceIn(marker, (size.width - marker).coerceAtLeast(marker))
                    drawLine(pointColor, Offset(center - marker, top + marker), Offset(center, top), 1.5.dp.toPx())
                    drawLine(pointColor, Offset(center, top), Offset(center + marker, top + marker), 1.5.dp.toPx())
                }
                previous = position
                previousEstimated = point.estimated
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = axisWidth), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.player_frame_drop_history_age, seconds), color = axisColor, fontSize = 10.sp, lineHeight = 12.sp)
        Text(stringResource(R.string.player_frame_drop_history_age, seconds / 2), color = axisColor, fontSize = 10.sp, lineHeight = 12.sp)
        Text(stringResource(R.string.player_frame_drop_history_now), color = axisColor, fontSize = 10.sp, lineHeight = 12.sp)
    }
}
