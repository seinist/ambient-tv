package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.theme.appControlSurface
import com.ambient.tv.ui.theme.menuFocusMotion
import kotlin.math.roundToInt

/**
 * Snaps [value] onto the [step] grid inside [range] after applying [deltaSteps] steps.
 * Hold/repeat callers pass a larger [deltaSteps] via [playerValueRepeatSteps].
 */
internal fun adjustPlayerIntValue(value: Int, range: IntRange, step: Int, deltaSteps: Int): Int {
    require(step > 0) { "step must be > 0" }
    if (deltaSteps == 0) return value.coerceIn(range.first, range.last)
    val start = range.first
    val end = range.last
    val snapped = start + ((value - start).toDouble() / step).roundToInt() * step
    val next = snapped + deltaSteps * step
    return next.coerceIn(start, end).let { v ->
        // Keep final value on the step grid relative to range.start.
        start + ((v - start).toDouble() / step).roundToInt() * step
    }.coerceIn(start, end)
}

/**
 * Float variant for half-step controls (e.g. 0.5 dB). Values are rounded to the nearest step.
 */
internal fun adjustPlayerFloatValue(
    value: Float,
    rangeStart: Float,
    rangeEnd: Float,
    step: Float,
    deltaSteps: Int,
): Float {
    require(step > 0f) { "step must be > 0" }
    if (deltaSteps == 0) return value.coerceIn(rangeStart, rangeEnd)
    val snapped = rangeStart + ((value - rangeStart) / step).roundToInt() * step
    val next = snapped + deltaSteps * step
    return (rangeStart + ((next - rangeStart) / step).roundToInt() * step)
        .coerceIn(rangeStart, rangeEnd)
}

/**
 * Maps Android key-repeat counts to extra step multipliers so a held D-pad key accelerates
 * without jumping wildly on the first few repeats.
 */
internal fun playerValueRepeatSteps(repeatCount: Int): Int = when {
    repeatCount <= 0 -> 1
    repeatCount < 8 -> 1
    repeatCount < 20 -> 2
    else -> 4
}

/** Fraction of the track filled for a bipolar or unipolar integer range. */
internal fun playerValueProgress(value: Int, range: IntRange): Float {
    val span = (range.last - range.first).toFloat()
    if (span <= 0f) return 0f
    return ((value - range.first) / span).coerceIn(0f, 1f)
}

internal fun playerValueProgress(value: Float, rangeStart: Float, rangeEnd: Float): Float {
    val span = rangeEnd - rangeStart
    if (span <= 0f) return 0f
    return ((value - rangeStart) / span).coerceIn(0f, 1f)
}

internal fun playerIntValueAtFraction(fraction: Float, range: IntRange, step: Int): Int {
    require(step > 0) { "step must be > 0" }
    val raw = range.first + (range.last - range.first) * fraction.coerceIn(0f, 1f)
    return (range.first + ((raw - range.first) / step).roundToInt() * step).coerceIn(range.first, range.last)
}

internal fun playerFloatValueAtFraction(
    fraction: Float,
    rangeStart: Float,
    rangeEnd: Float,
    step: Float,
): Float {
    require(step > 0f) { "step must be > 0" }
    val raw = rangeStart + (rangeEnd - rangeStart) * fraction.coerceIn(0f, 1f)
    return (rangeStart + ((raw - rangeStart) / step).roundToInt() * step).coerceIn(rangeStart, rangeEnd)
}

/**
 * Kodi-like fine numeric slider for Leanback/TV: focused row shows the current value; D-pad
 * left/right (including hold/repeat) adjusts by [step]. Handheld drag is supported. Up/down are
 * left alone so focus can move between stacked player controls.
 */
@Composable
fun PlayerValueSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    valueLabel: String = value.toString(),
    onValueChange: (Int) -> Unit,
) {
    val onChange by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    val range by rememberUpdatedState(valueRange)
    val stepSize by rememberUpdatedState(step)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val progress = playerValueProgress(value, valueRange)
    val a11y = stringResource(R.string.player_value_slider_cd, label, valueLabel)
    val density = LocalDensity.current
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val dragThresholdPx = with(density) { 18.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .menuFocusMotion(focused, focusedScale = 1.006f)
            .appControlSurface(focused = focused, shape = shape)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    steps = ((valueRange.last - valueRange.first) / stepSize).coerceAtLeast(0),
                )
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                val delta = direction * playerValueRepeatSteps(event.nativeKeyEvent.repeatCount)
                val next = adjustPlayerIntValue(current, range, stepSize, delta)
                if (next != current) onChange(next)
                true
            }
            .focusOnTvPointer()
            .onTvPointerScroll { delta ->
                val scroll = delta.let { if (kotlin.math.abs(it.y) >= kotlin.math.abs(it.x)) it.y else it.x }
                if (scroll == 0f) return@onTvPointerScroll false
                val next = adjustPlayerIntValue(current, range, stepSize, if (scroll < 0f) 1 else -1)
                if (next != current) onChange(next)
                true
            }
            .pointerInput(valueRange, step) {
                detectTapGestures { position ->
                    val next = playerIntValueAtFraction(position.x / size.width.coerceAtLeast(1), range, stepSize)
                    if (next != current) onChange(next)
                }
            }
            .pointerInput(valueRange, step) {
                var live = current
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f; live = current },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount
                        while (dragAccum <= -dragThresholdPx) {
                            dragAccum += dragThresholdPx
                            val next = adjustPlayerIntValue(live, range, stepSize, -1)
                            if (next != live) { live = next; onChange(next) }
                        }
                        while (dragAccum >= dragThresholdPx) {
                            dragAccum -= dragThresholdPx
                            val next = adjustPlayerIntValue(live, range, stepSize, 1)
                            if (next != live) { live = next; onChange(next) }
                        }
                    },
                )
            }
            .focusable()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.78f else 0.58f),
                fontSize = 12.sp,
            )
        }
        PlayerValueTrack(progress = progress, focused = focused)
    }
}

/**
 * Float slider for fine half-step ranges (e.g. 0.5 dB). Same TV focus / hold behavior as the
 * integer control.
 */
@Composable
fun PlayerValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    step: Float = 0.5f,
    valueLabel: String = formatPlayerFloatLabel(value),
    onValueChange: (Float) -> Unit,
) {
    val onChange by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    val rangeStart = valueRange.start
    val rangeEnd = valueRange.endInclusive
    val stepSize by rememberUpdatedState(step)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val progress = playerValueProgress(value, rangeStart, rangeEnd)
    val a11y = stringResource(R.string.player_value_slider_cd, label, valueLabel)
    val density = LocalDensity.current
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val dragThresholdPx = with(density) { 18.dp.toPx() }
    val steps = if (step > 0f) ((rangeEnd - rangeStart) / step).roundToInt().coerceAtLeast(0) else 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .menuFocusMotion(focused, focusedScale = 1.006f)
            .appControlSurface(focused = focused, shape = shape)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = rangeStart..rangeEnd,
                    steps = steps,
                )
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                val delta = direction * playerValueRepeatSteps(event.nativeKeyEvent.repeatCount)
                val next = adjustPlayerFloatValue(current, rangeStart, rangeEnd, stepSize, delta)
                if (next != current) onChange(next)
                true
            }
            .focusOnTvPointer()
            .onTvPointerScroll { delta ->
                val scroll = delta.let { if (kotlin.math.abs(it.y) >= kotlin.math.abs(it.x)) it.y else it.x }
                if (scroll == 0f) return@onTvPointerScroll false
                val next = adjustPlayerFloatValue(current, rangeStart, rangeEnd, stepSize, if (scroll < 0f) 1 else -1)
                if (next != current) onChange(next)
                true
            }
            .pointerInput(valueRange, step) {
                detectTapGestures { position ->
                    val next = playerFloatValueAtFraction(position.x / size.width.coerceAtLeast(1), rangeStart, rangeEnd, stepSize)
                    if (next != current) onChange(next)
                }
            }
            .pointerInput(valueRange, step) {
                var live = current
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f; live = current },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount
                        while (dragAccum <= -dragThresholdPx) {
                            dragAccum += dragThresholdPx
                            val next = adjustPlayerFloatValue(live, rangeStart, rangeEnd, stepSize, -1)
                            if (next != live) { live = next; onChange(next) }
                        }
                        while (dragAccum >= dragThresholdPx) {
                            dragAccum -= dragThresholdPx
                            val next = adjustPlayerFloatValue(live, rangeStart, rangeEnd, stepSize, 1)
                            if (next != live) { live = next; onChange(next) }
                        }
                    },
                )
            }
            .focusable()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.78f else 0.58f),
                fontSize = 12.sp,
            )
        }
        PlayerValueTrack(progress = progress, focused = focused)
    }
}

@Composable
private fun PlayerValueTrack(progress: Float, focused: Boolean) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 1f else 0.82f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(track, RoundedCornerShape(999.dp)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(fill, RoundedCornerShape(999.dp)),
        )
    }
}

internal fun formatPlayerFloatLabel(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) {
        val i = rounded.toInt()
        if (i > 0) "+$i" else i.toString()
    } else {
        val text = if (rounded > 0) "+$rounded" else rounded.toString()
        text.trimEnd('0').trimEnd('.')
    }
}

/** Signed integer label used by picture levels (+5 / 0 / -5). */
internal fun formatSignedIntLabel(value: Int): String = if (value > 0) "+$value" else value.toString()

/** Decibel label for dialogue / preamp / EQ (+3 dB / 0 dB / -4 dB). */
internal fun formatDbLabel(value: Int, showPlus: Boolean = true): String = when {
    value > 0 && showPlus -> "+$value dB"
    else -> "$value dB"
}
