package com.ambient.tv.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay

private val techBadgesVisibleState = mutableStateOf<Boolean?>(null)

@Composable
fun playerTechBadgesVisible(): Boolean {
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val state = remember(preferences) {
        if (techBadgesVisibleState.value == null) {
            techBadgesVisibleState.value = preferences.playerTechBadgesVisible
        }
        techBadgesVisibleState
    }
    return state.value != false
}

fun setPlayerTechBadgesVisible(preferences: AppPreferences, visible: Boolean) {
    techBadgesVisibleState.value = visible
    preferences.playerTechBadgesVisible = visible
}

private val BadgeRowFade = 24.dp

/**
 * Which edges of the badge line fade out: only an edge where badges are actually cut off.
 * [scrollPx]/[maxScrollPx] describe a scrollable line; a marquee ([marquee]) moves its content
 * past both edges, so both fade while it overflows.
 */
internal fun playerBadgeRowFades(overflowing: Boolean, marquee: Boolean, scrollPx: Int, maxScrollPx: Int): Pair<Boolean, Boolean> = when {
    !overflowing -> false to false
    marquee -> true to true
    else -> (scrollPx > 0) to (scrollPx < maxScrollPx)
}

private fun Modifier.badgeEdgeFade(start: Boolean, end: Boolean, width: Dp): Modifier =
    if (!start && !end) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = width.toPx().coerceAtMost(size.width / 2f)
            if (start) drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                blendMode = BlendMode.DstIn,
            )
            if (end) drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                blendMode = BlendMode.DstIn,
            )
        }

@Composable
fun PlayerTechBadgeRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (!playerTechBadgesVisible()) return
    if (LocalHandheldUi.current) HandheldBadgeRow(modifier, content) else TvBadgeRow(modifier, content)
}

/**
 * TV: the badges are not focusable (D-pad stays on seek bar and controls), so a line that is
 * wider than the OSD runs as a slow marquee. basicMarquee only moves when the content overflows.
 */
@Composable
private fun TvBadgeRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    var visibleWidth by remember { mutableIntStateOf(0) }
    var contentWidth by remember { mutableIntStateOf(0) }
    val overflowing = visibleWidth > 0 && contentWidth > visibleWidth
    val (fadeStart, fadeEnd) = playerBadgeRowFades(overflowing, marquee = true, scrollPx = 0, maxScrollPx = 0)
    Row(
        modifier = modifier
            .onSizeChanged { visibleWidth = it.width }
            .badgeEdgeFade(fadeStart, fadeEnd, BadgeRowFade)
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1_500,
                repeatDelayMillis = 2_000,
                spacing = MarqueeSpacing(48.dp),
                velocity = 30.dp,
            )
            // After the marquee: the content measured at its full, unclipped width.
            .onSizeChanged { contentWidth = it.width },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Phone: swipe the line; once per player it scrolls to the end and back as a hint. */
@Composable
private fun HandheldBadgeRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val scroll = rememberScrollState()
    val overflowing = scroll.maxValue > 0
    var hinted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(overflowing) {
        if (!overflowing || hinted) return@LaunchedEffect
        delay(1_200)
        hinted = true
        val millis = (scroll.maxValue * 4).coerceIn(600, 3_000)
        scroll.animateScrollTo(scroll.maxValue, tween(millis))
        delay(900)
        scroll.animateScrollTo(0, tween(millis))
    }
    val (fadeStart, fadeEnd) = playerBadgeRowFades(overflowing, marquee = false, scroll.value, scroll.maxValue)
    Row(
        modifier = modifier
            .badgeEdgeFade(fadeStart, fadeEnd, BadgeRowFade)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
