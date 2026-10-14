package com.ambient.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import com.ambient.tv.ui.common.FullscreenSpinner
import kotlinx.coroutines.delay

private const val BUFFERING_SPINNER_DELAY_MS = 280L

/** True while the engine intends to play but has not produced a picture (or audio) yet. */
fun waitingForPlaybackPicture(wantsToPlay: Boolean, isPlaying: Boolean): Boolean =
    wantsToPlay && !isPlaying

/** True while Media3 is preparing or rebuffering with play intent. */
fun media3WaitingForPlaybackPicture(playbackState: Int, playWhenReady: Boolean): Boolean =
    playWhenReady &&
        playbackState != Player.STATE_READY &&
        playbackState != Player.STATE_ENDED

/**
 * Centered [FullscreenSpinner] for the black player surface. Delayed slightly so brief
 * seeks and instant starts do not flash it.
 */
@Composable
fun PlayerBufferingSpinner(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(BUFFERING_SPINNER_DELAY_MS)
            shown = true
        } else {
            shown = false
        }
    }
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(160)),
        exit = fadeOut(animationSpec = tween(110)),
    ) {
        FullscreenSpinner()
    }
}
