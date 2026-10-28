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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.NumberFormat

/** Denominator is explicit: expected frames for the watchdog, rendered frames for the overlay. */
internal fun frameDropPercent(dropped: Long?, denominator: Double?): Double? =
    if (dropped == null || dropped < 0 || denominator == null || !denominator.isFinite() || denominator <= 0) null
    else (100.0 * dropped / denominator).takeIf { it.isFinite() }

internal fun formatFramePercent(value: Double?): String = value?.let {
    NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(it) + " %"
} ?: "—"

internal data class FrameDropReading(
    val dropped: Long? = null,
    val rendered: Long? = null,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val fps: Double? = null,
    val estimated: Boolean = false,
)

/** Short-window ratio, with discontinuities excluded rather than counted as rendered video. */
internal fun frameDropWindowPercent(previous: FrameDropReading?, current: FrameDropReading): Double? {
    if (previous == null || !previous.playing || !current.playing || previous.estimated != current.estimated) return null
    val dropped = current.dropped ?: return null
    val oldDropped = previous.dropped ?: return null
    val delta = dropped - oldDropped
    if (delta < 0) return null
    val rendered = if (current.estimated) {
        val elapsed = current.positionMs - previous.positionMs
        if (elapsed !in 1..2500) return null
        val fps = current.fps?.takeIf { it.isFinite() && it > 0 } ?: return null
        (fps * elapsed / 1000.0 - delta).coerceAtLeast(0.0)
    } else {
        val count = current.rendered ?: return null
        val oldCount = previous.rendered ?: return null
        (count - oldCount).toDouble()
    }
    return frameDropPercent(delta, rendered)
}

private fun readFrameDrops(handle: PlaybackHandle?): FrameDropReading {
    val status = handle?.status()
    return when (handle) {
        is MpvPlaybackController -> FrameDropReading(
            dropped = handle.frameDropCount(),
            positionMs = status!!.positionMs,
            playing = status.playing,
            fps = if (handle.usesFrameInterpolation) handle.displayFps() else handle.containerFps(),
            estimated = true,
        )
        is VlcPlaybackController -> handle.runtimeStats()?.let {
            FrameDropReading(it.lostPictures?.toLong(), it.displayedPictures?.toLong(), playing = status!!.playing)
        } ?: FrameDropReading()
        is Media3PlaybackHandle -> handle.player.videoDecoderCounters?.let {
            it.ensureUpdated()
            FrameDropReading(it.droppedBufferCount.toLong(), it.renderedOutputBufferCount.toLong(), playing = status!!.playing)
        } ?: FrameDropReading()
        else -> FrameDropReading()
    }
}

/** Share of frames the watchdog tolerates before it steps in (see ImageProcessingPolicy). */
internal const val FRAME_DROP_WATCHDOG_PERCENT = 10.0

/** Bar fill: full while nothing drops, half at the watchdog threshold, empty at twice that. */
internal fun frameDropBarFill(dropPercent: Double?): Float =
    if (dropPercent == null) 1f else (1.0 - dropPercent / (2 * FRAME_DROP_WATCHDOG_PERCENT)).coerceIn(0.0, 1.0).toFloat()

/** Exponential smoothing so single-second spikes do not make the bar jump. */
internal fun smoothFrameDrop(previous: Double?, sample: Double?): Double? = when {
    sample == null -> previous
    previous == null -> sample
    else -> previous + 0.35 * (sample - previous)
}

private val frameDropColorStops = listOf(
    0.0 to Color(0xFF66BB6A),
    0.5 to Color(0xFFA5D66B),
    2.0 to Color(0xFFFFB74D),
    FRAME_DROP_WATCHDOG_PERCENT to Color(0xFFEF5350),
)

/** Green while (almost) every frame is shown, through amber to red at the watchdog threshold. */
internal fun frameDropBarColor(dropPercent: Double?): Color {
    val value = dropPercent ?: 0.0
    frameDropColorStops.zipWithNext().forEach { (from, to) ->
        if (value <= to.first) return lerp(from.second, to.second, ((value - from.first) / (to.first - from.first)).coerceIn(0.0, 1.0).toFloat())
    }
    return frameDropColorStops.last().second
}

/**
 * Window overlay shown while a video panel is open; fades in and out and never takes focus or handles Back.
 * A slim bar of shown frames over the last seconds, coloured and scaled like the render watchdog sees it.
 * Hosted by [PlayerPanelVisibility] so it outlives the panel long enough to fade out.
 */
@Composable
internal fun PlaybackFrameDropOverlay(visible: Boolean) {
    val handle = BackgroundPlaybackSession.activeHandle()
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible && (handle is MpvPlaybackController || handle is VlcPlaybackController || handle is Media3PlaybackHandle)
    if (!visibility.currentState && !visibility.targetState) return
    var estimated by remember(handle) { mutableStateOf(false) }
    var smoothed by remember(handle) { mutableStateOf<Double?>(null) }
    LaunchedEffect(handle) {
        var previous: FrameDropReading? = null
        while (isActive) {
            val current = readFrameDrops(handle)
            smoothed = smoothFrameDrop(smoothed, frameDropWindowPercent(previous, current))
            estimated = current.estimated
            previous = current
            delay(1_000)
        }
    }
    val fill by animateFloatAsState(frameDropBarFill(smoothed), tween(900, easing = FastOutSlowInEasing), label = "frameDropFill")
    val color by animateColorAsState(frameDropBarColor(smoothed), tween(900), label = "frameDropColor")
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
                    text = (if (estimated && smoothed != null) "≈" else "") + formatFramePercent(smoothed),
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
