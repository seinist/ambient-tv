package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Text uses the hero's real height. Measure badges/progress first so a long title cannot push
 * them out, then share the remaining space between title, context and summary. Each child is
 * measured once; no onTextLayout state changes or extra compositions are needed for fitting.
 */
@Composable
internal fun HeroTextLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    titleMinHeight: Dp,
    contextMinHeight: Dp = 0.dp,
    summaryMinHeight: Dp = 0.dp,
    title: @Composable () -> Unit,
    context: (@Composable () -> Unit)? = null,
    metadata: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.fillMaxWidth()) { title() }
            Box(Modifier.fillMaxWidth()) { context?.invoke() }
            Box(Modifier.fillMaxWidth()) { metadata() }
            Box(Modifier.fillMaxWidth()) { summary?.invoke() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val metadataPlaceable = measurables[2].measure(loose)
        val hasMetadata = metadataPlaceable.height > 0
        val slotCount = 1 + (if (context != null) 1 else 0) +
            (if (hasMetadata) 1 else 0) + (if (summary != null) 1 else 0)
        val gapCount = slotCount - 1
        val spaceAfterMetadata = (constraints.maxHeight - metadataPlaceable.height).coerceAtLeast(0)
        // At extreme font/UI scales, spacing must not push the protected metadata out of view.
        val gap = if (gapCount == 0) 0 else spacing.roundToPx().coerceIn(0, spaceAfterMetadata / gapCount)
        val gaps = gap * gapCount
        var remaining = spaceAfterMetadata - gaps
        val contextReserve = if (context != null) contextMinHeight.roundToPx() else 0
        val summaryReserve = if (summary != null) summaryMinHeight.roundToPx() else 0

        val titleBudget = (remaining - contextReserve - summaryReserve)
            .coerceAtLeast(titleMinHeight.roundToPx()).coerceIn(0, remaining)
        val titlePlaceable = measurables[0].measure(loose.copy(maxHeight = titleBudget))
        remaining = (remaining - titlePlaceable.height).coerceAtLeast(0)
        val contextBudget = if (context == null) 0 else (remaining - summaryReserve)
            .coerceAtLeast(contextReserve).coerceIn(0, remaining)
        val contextPlaceable = measurables[1].measure(loose.copy(maxHeight = contextBudget))
        remaining = (remaining - contextPlaceable.height).coerceAtLeast(0)
        val summaryPlaceable = measurables[3].measure(loose.copy(maxHeight = if (summary == null) 0 else remaining))

        val ordered = buildList {
            add(titlePlaceable)
            if (context != null) add(contextPlaceable)
            if (hasMetadata) add(metadataPlaceable)
            if (summary != null) add(summaryPlaceable)
        }
        val width = constraints.constrainWidth(ordered.maxOf { it.width })
        val height = constraints.constrainHeight(ordered.sumOf { it.height } + gaps)
        layout(width, height) {
            var y = 0
            ordered.forEach { child ->
                child.placeRelative(0, y)
                y += child.height + gap
            }
        }
    }
}
