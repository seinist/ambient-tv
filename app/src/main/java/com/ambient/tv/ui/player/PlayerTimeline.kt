package com.ambient.tv.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.theme.LocalAccentStyle
import com.ambient.tv.ui.theme.LocalPlaybackAccent
import com.ambient.tv.ui.theme.accentBrush
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Call outside OSD visibility so hiding controls does not discard the current item's duration. */
@Composable
internal fun rememberPlayerTimelineDuration(
    sourceIdentity: String,
    durationMs: Long,
    retainDuration: Boolean = true,
    fallbackDurationMs: Long = 0L,
): Long {
    val localDuration = remember { mutableStateOf(PlayerTimelineDuration()) }
    val memory = LocalPlayerChromeState.current?.timelineDuration ?: localDuration
    val next = memory.value.update(sourceIdentity, durationMs, retainDuration, fallbackDurationMs)
    SideEffect { memory.value = next }
    return next.durationMs
}

@Composable
private fun playerTimelineMotionMs(): Int =
    if (LocalPerformanceProfile.current.reducedEffects) 120 else 180

/**
 * One shared fade for the thin current-position line on TV and handheld timelines.
 *
 * Retain its last on-screen coordinate while the alpha tweens to zero; conditional
 * composition used to remove the Canvas immediately, skipping the exit animation.
 * Changing opacity only never changes the seekbar's measured height or focus targets.
 */
internal data class SeekPlayheadVisual(val fraction: Float, val alpha: Float)

@Composable
internal fun rememberSeekPlayheadVisual(fraction: Float?): SeekPlayheadVisual {
    val lastPosition = remember { mutableFloatStateOf(fraction ?: 0f) }
    SideEffect { if (fraction != null) lastPosition.floatValue = fraction }
    val reducedEffects = LocalPerformanceProfile.current.reducedEffects
    val visible = fraction != null
    // Start at zero even if the OSD is first composed during a direct seek. Unlike
    // animateFloatAsState(target = 1), this also fades *in* on that first frame.
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(visible, reducedEffects) {
        opacity.animateTo(
            if (visible) 1f else 0f,
            tween(
                seekBarPlayheadFadeDurationMs(showing = visible, reducedEffects = reducedEffects),
                easing = FastOutSlowInEasing,
            ),
        )
    }
    return SeekPlayheadVisual(seekBarPlayheadDrawFraction(fraction, lastPosition.floatValue), opacity.value)
}

/** Passive live/radio OSD timeline: same accent material and track geometry as the seekbar. */
internal fun playerOsdProgressFraction(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

@Composable
internal fun PlayerOsdProgressTrack(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val accent = LocalPlaybackAccent.current.indicator
    val style = LocalAccentStyle.current
    val fill = remember(accent, style) { accentBrush(accent, style) }
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().height(height)
        .clip(shape).background(Color.White.copy(alpha = .24f), shape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(playerOsdProgressFraction(progress))
            .background(fill, shape))
    }
}

/**
 * The loading bar already has its final device-specific track/time height. Only genuinely
 * new rows (chapters/programme information) or a live/DVR mode change need a size transition.
 * Keep the bottom edge anchored and one interactive subtree throughout the transition.
 * Unlike animateContentSize, this must not permanently clip: the scrub thumb and bookmark
 * ticks extend beyond the track. During growth, reveal new rows inside the allocated area
 * plus that small overhang so they cannot paint over the header while it moves upwards.
 */
@Composable
internal fun PlayerTimelineSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motionMs = playerTimelineMotionMs()
    val scope = rememberCoroutineScope()
    val sizeMotion = remember { PlayerTimelineSizeMotion() }
    val trackOverhang = com.ambient.tv.ui.mobile.deviceDp(6.dp, 0.dp)
    Box(
        modifier = modifier.fillMaxWidth().drawWithContent {
            if (sizeMotion.targetHeight > size.height) {
                clipRect(
                    left = -16.dp.toPx(),
                    top = -trackOverhang.toPx(),
                    right = size.width + 16.dp.toPx(),
                    bottom = size.height + trackOverhang.toPx(),
                ) { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }.layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            // Start at the first measured height, rather than animating from an empty slot.
            val heightMotion = sizeMotion.height ?: Animatable(child.height.toFloat()).also {
                sizeMotion.height = it
                sizeMotion.targetHeight = child.height
            }
            if (sizeMotion.targetHeight != child.height) {
                sizeMotion.targetHeight = child.height
                sizeMotion.job?.cancel()
                sizeMotion.job = scope.launch {
                    heightMotion.animateTo(
                        child.height.toFloat(),
                        tween(motionMs, easing = FastOutSlowInEasing),
                    )
                }
            }
            // Reading in measurement invalidates layout, not the whole player composition.
            val height = constraints.constrainHeight(heightMotion.value.roundToInt())
            layout(child.width, height) {
                child.placeRelative(0, height - child.height)
            }
        },
        contentAlignment = Alignment.BottomStart,
    ) { content() }
}

private class PlayerTimelineSizeMotion {
    var height: Animatable<Float, AnimationVector1D>? = null
    var targetHeight: Int = -1
    var job: Job? = null
}

/** A known initial duration is immediately visible; only late data fades into the loading bar. */
@Composable
internal fun playerTimelineRevealAlpha(ready: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (ready) 1f else 0f,
    animationSpec = tween(playerTimelineMotionMs(), easing = FastOutSlowInEasing),
    label = "Timeline data",
)

/** Crossfade the loading readout only. Normal clock ticks never restart the animation. */
@Composable
internal fun PlayerTimelineTimeText(
    ready: Boolean,
    text: String,
    color: Color,
    placeholder: String = "--:--",
) {
    Crossfade(
        targetState = ready,
        animationSpec = tween(playerTimelineMotionMs()),
        label = "Timeline time",
    ) { available ->
        Text(
            // A new item's unknown duration must never show the outgoing item's time.
            text = if (available && ready) text else placeholder,
            color = if (available && ready) color else color.copy(alpha = 0.42f),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}
