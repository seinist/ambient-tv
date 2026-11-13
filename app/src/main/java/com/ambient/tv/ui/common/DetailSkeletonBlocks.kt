package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.mobile.DeviceActionRow

/**
 * Building blocks for detail-page loading states. A single shared pulse is applied at draw time,
 * not by recomposing each placeholder; Sparsam uses a static fill instead.
 * None of these blocks is a focus target or an actionable button.
 */
@Composable
fun DetailSkeletonBlock(
    modifier: Modifier,
    radius: Dp = 8.dp,
    accent: Boolean = false,
) {
    val pulse = rememberSkeletonPulseOrNull()
    val fill = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.clip(RoundedCornerShape(radius)).skeletonPulseAlpha(pulse).background(fill))
}

/** Synopsis-sized bars; the final line is intentionally shorter like a real paragraph. */
@Composable
fun DetailSkeletonParagraph(
    modifier: Modifier = Modifier,
    lines: Int = 4,
    lineHeight: Dp = 14.dp,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { index ->
            val fraction = when {
                index == lines - 1 -> 0.57f
                index % 3 == 1 -> 0.84f
                else -> 0.96f
            }
            DetailSkeletonBlock(Modifier.fillMaxWidth(fraction).height(lineHeight), radius = 5.dp)
        }
    }
}

/** Keep the primary and secondary button footprints without creating disabled focus targets. */
@Composable
fun DetailSkeletonActions(modifier: Modifier = Modifier, stack: Boolean = false, secondary: Boolean = true) {
    DeviceActionRow(
        modifier = modifier,
        stack = stack,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailSkeletonBlock(Modifier.width(176.dp).height(48.dp), radius = 10.dp, accent = true)
        if (secondary) DetailSkeletonBlock(Modifier.width(132.dp).height(48.dp), radius = 10.dp)
    }
}
