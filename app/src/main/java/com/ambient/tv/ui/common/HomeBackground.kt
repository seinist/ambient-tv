package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ambient.tv.ui.home.HomeHeroContent
import kotlinx.coroutines.flow.MutableStateFlow

/** Browse pages reveal the single root ambient layer instead of painting over it. */
val LocalHomeBackgroundVisible = staticCompositionLocalOf { false }
val LocalHomeBackgroundArtwork = staticCompositionLocalOf<MutableStateFlow<HomeHeroContent?>?> { null }

/** Off removes the focused item's artwork backdrop on Home, library and YouTube pages. */
val LocalFocusArtworkBackdrop = staticCompositionLocalOf { true }

/**
 * The root flag follows the current route, so it flips the moment Back pops a page while
 * NavHost still draws that page on top for a few frames. A leaving destination (lifecycle
 * dropped below STARTED after it was shown) keeps the value it was shown with and stops
 * drawing, so the see-through page neither swaps its backdrop nor ghosts over its parent.
 */
@Composable
fun HoldHomeBackgroundWhileLeaving(content: @Composable () -> Unit) {
    val live = LocalHomeBackgroundVisible.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val held = remember { mutableStateOf(live) }
    val shown = remember { mutableStateOf(false) }
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    if (active) {
        held.value = live
        shown.value = true
    }
    val leaving = shown.value && !active
    CompositionLocalProvider(LocalHomeBackgroundVisible provides held.value) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (leaving) 0f else 1f }) { content() }
    }
}
