package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlin.math.roundToInt

/**
 * One preview per player OSD, above its regular layout. The timeline only reports the target
 * and its measured track; it never measures the preview as one of its Column children.
 * The state lives with PlayerOverlayVisibility, not with an individual playback renderer.
 */
internal class SeekPreviewOverlayState {
    var positionMs by mutableStateOf<Long?>(null)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var showing by mutableStateOf(false)
        private set
    var trackBoundsInRoot by mutableStateOf<Rect?>(null)

    fun update(positionMs: Long?, durationMs: Long, hasPreview: Boolean) {
        val target = positionMs?.takeIf { durationMs > 0L && hasPreview }
        this.positionMs = target
        this.durationMs = durationMs
        showing = target != null
    }

    fun hide() {
        positionMs = null
        showing = false
    }
}

/** The preview's fade-out; OSD content above the seekbar returns after it. */
internal const val SEEK_PREVIEW_EXIT_MS = 260

internal val LocalSeekPreviewOverlayState = staticCompositionLocalOf<SeekPreviewOverlayState?> { null }

/** Keep the bubble centered on the scrub target without covering or escaping screen edges. */
internal fun seekPreviewScreenX(
    trackLeft: Float,
    trackWidth: Float,
    viewportLeft: Float,
    viewportWidth: Float,
    cardWidth: Float,
    progress: Float,
    margin: Float,
): Float {
    if (!trackLeft.isFinite() || !trackWidth.isFinite() || !viewportLeft.isFinite() ||
        !viewportWidth.isFinite() || !cardWidth.isFinite() || viewportWidth <= 0f) return 0f
    val minX = margin.coerceIn(0f, viewportWidth / 2f)
    val maxX = (viewportWidth - cardWidth - margin).coerceAtLeast(minX)
    val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return (trackLeft - viewportLeft + trackWidth.coerceAtLeast(0f) * fraction - cardWidth / 2f)
        .coerceIn(minX, maxX)
}

/** Never shift the seekbar or metadata; clamp the floating bubble to the player's viewport. */
internal fun seekPreviewScreenY(
    trackTop: Float,
    viewportTop: Float,
    viewportHeight: Float,
    cardHeight: Float,
    gap: Float,
    topMargin: Float,
): Float {
    if (!trackTop.isFinite() || !viewportTop.isFinite() || !viewportHeight.isFinite() ||
        !cardHeight.isFinite() || viewportHeight <= 0f) return 0f
    val minY = topMargin.coerceAtLeast(0f)
    val maxY = (viewportHeight - cardHeight - minY).coerceAtLeast(minY)
    return (trackTop - viewportTop - cardHeight - gap.coerceAtLeast(0f)).coerceIn(minY, maxY)
}

@Composable
internal fun BoxScope.SeekPreviewOverlay(
    state: SeekPreviewOverlayState,
    viewportOrigin: Offset,
    viewportSize: IntSize,
) {
    val navigation = LocalPlaybackNavigation.current ?: return
    val timeline = navigation.timeline
    val target = state.positionMs
    // Retain the last image until its exit fade has finished.
    var lastTarget by remember(navigation) { mutableStateOf<Long?>(null) }
    SideEffect { if (target != null) lastTarget = target }
    val shownPosition = target ?: lastTarget ?: return
    val frame = timeline.previewFrameAt(shownPosition)
    val chapter = timeline.chapterAt(shownPosition)
    val track = state.trackBoundsInRoot
    val show = target != null && (frame != null || chapter != null) &&
        track != null && viewportSize.width > 0 && viewportSize.height > 0

    val density = LocalDensity.current
    val margin = with(density) { 12.dp.toPx() }
    val desiredWidth = with(density) { (if (LocalHandheldUi.current) 152.dp else 208.dp).toPx() }
    val widthPx = desiredWidth.coerceAtMost((viewportSize.width - 2f * margin).coerceAtLeast(1f))
    var measuredHeight by remember { mutableIntStateOf(0) }
    val estimatedHeight = if (frame != null) widthPx * 9f / 16f else with(density) { 62.dp.toPx() }
    val heightPx = measuredHeight.takeIf { it > 0 }?.toFloat() ?: estimatedHeight
    val fraction = (shownPosition.toDouble() / state.durationMs.coerceAtLeast(1L)).toFloat()
    val x = track?.let {
        seekPreviewScreenX(it.left, it.width, viewportOrigin.x, viewportSize.width.toFloat(),
            widthPx, fraction, margin)
    } ?: 0f
    val y = track?.let {
        seekPreviewScreenY(it.top, viewportOrigin.y, viewportSize.height.toFloat(),
            heightPx, with(density) { 12.dp.toPx() }, margin)
    } ?: 0f

    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(Alignment.TopStart)
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        enter = fadeIn(tween(140, delayMillis = 80)),
        exit = fadeOut(tween(SEEK_PREVIEW_EXIT_MS)),
    ) {
        SeekPreviewCard(
            positionMs = shownPosition,
            modifier = Modifier.width(with(density) { widthPx.toDp() })
                .onSizeChanged { if (measuredHeight != it.height) measuredHeight = it.height },
        )
    }
}
