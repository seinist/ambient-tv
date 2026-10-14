@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * Window overlay shown while a video panel is open; fades in and out and never takes focus or handles Back.
 * Hosted by [PlayerPanelVisibility] so it outlives the panel long enough to fade out.
 */
@Composable
internal fun PlaybackFrameDropOverlay(visible: Boolean) {
    val handle = BackgroundPlaybackSession.activeHandle()
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible && (handle is MpvPlaybackController || handle is VlcPlaybackController || handle is Media3PlaybackHandle)
    if (!visibility.currentState && !visibility.targetState) return
    var reading by remember(handle) { mutableStateOf(FrameDropReading()) }
    var percent by remember(handle) { mutableStateOf<Double?>(null) }
    LaunchedEffect(handle) {
        var previous: FrameDropReading? = null
        while (isActive) {
            val current = readFrameDrops(handle)
            percent = frameDropWindowPercent(previous, current)
            reading = current
            previous = current
            delay(1_000)
        }
    }
    val inset = with(LocalDensity.current) { IntOffset(24.dp.roundToPx(), 28.dp.roundToPx()) }
    val position = remember(inset) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
            IntOffset(inset.x.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                inset.y.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    } }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        AnimatedVisibility(visibility, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Text(
                text = stringResource(
                    if (reading.estimated && percent != null) R.string.player_frame_drop_compact_estimated else R.string.player_frame_drop_compact,
                    reading.dropped?.toString() ?: "—",
                    formatFramePercent(percent),
                ),
                color = Color.White,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
