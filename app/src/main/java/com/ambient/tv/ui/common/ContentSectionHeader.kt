package com.ambient.tv.ui.common

import com.ambient.tv.ui.theme.screenGutter
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Shared heading for a logical content section/subcategory.
 *
 * Section identity is presentation-independent: callers use this same heading for rows,
 * lists, grids, walls and cinematic layouts so changing a view mode never drops the
 * information architecture of the source feed.
 */
@Composable
fun ContentSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    sectionKey: String? = null,
    focused: Boolean = false,
    startPadding: Dp? = null,
    bottomPadding: Dp = 10.dp,
) {
    if (title.isBlank()) return
    val motion = !LocalPerformanceProfile.current.reducedEffects
    val indicatorScale by animateFloatAsState(
        targetValue = if (focused) 1f else 14f / 22f,
        animationSpec = if (motion) tween(durationMillis = 180) else snap(),
        label = "sectionIndicatorScale",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0.5f,
        animationSpec = if (motion) tween(durationMillis = 180) else snap(),
        label = "sectionIndicatorAlpha",
    )
    val titleColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = if (motion) tween(durationMillis = 180) else snap(),
        label = "sectionTitleColor",
    )
    val system = remember(sectionKey) {
        sectionKey?.let { MediaSystem.fromKey(it.substringBefore('.').substringBefore(':')) }
    }
    val resolvedStartPadding = startPadding ?: screenGutter()
    Row(
        modifier = modifier.padding(start = resolvedStartPadding, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (system != null) {
            SystemLogo(system, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
        } else {
            Box(
                Modifier
                    // Focus must not remeasure the row while its parent is centering it.
                    .size(4.dp, 22.dp)
                    .graphicsLayer { scaleY = indicatorScale; alpha = indicatorAlpha }
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge, color = titleColor)
    }
}
