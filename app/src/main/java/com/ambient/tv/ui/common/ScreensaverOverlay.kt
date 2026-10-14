package com.ambient.tv.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/** Keep the same content/configuration mounted until its exit animation has finished. */
@Composable
internal fun ScreensaverOverlay(visible: Boolean, onDismiss: () -> Unit, onHidden: () -> Unit, content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    val hidden by rememberUpdatedState(onHidden)
    BackHandler(enabled = visible || visibility.currentState) { onDismiss() }
    LaunchedEffect(visible, visibility.currentState, visibility.isIdle) {
        if (!visible && !visibility.currentState && visibility.isIdle) hidden()
    }
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(ScreensaverPresentation.FADE_IN_MS, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(ScreensaverPresentation.FADE_OUT_MS, easing = FastOutLinearInEasing)),
    ) { Box(Modifier.fillMaxSize()) { content() } }
}
