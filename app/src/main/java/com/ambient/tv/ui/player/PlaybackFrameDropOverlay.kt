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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
    LaunchedEffect(handle) {
        var previous: FrameDropReading? = null
        while (isActive) {
            val current = readFrameDrops(handle)
            smoothed = smoothFrameDrop(smoothed, frameDropWindow(previous, current))
            targetFps = current.targetFps
            previous = current
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
    // The FPS overlay sits in the same corner; stack below it instead of drawing on top.
    val fpsBounds = PlaybackStatsOverlayLayout.fpsBounds
    val position = remember(inset, gap, fpsBounds) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
            stackedStatsPopupOffset(inset, popupContentSize, windowSize, fpsBounds, gap)
    } }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        AnimatedVisibility(visibility, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
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
        }
    }
}
