package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** Poll interval while the app is hidden (music in the background, screen off on a phone). */
internal const val BACKGROUND_POLL_MS = 1_000L

/** Picks [visibleMs] while the app is on screen and [BACKGROUND_POLL_MS] otherwise. */
internal fun playbackPollInterval(visibleMs: Long, appVisible: Boolean): Long =
    if (appVisible) visibleMs else maxOf(visibleMs, BACKGROUND_POLL_MS)

/**
 * True while the hosting Activity is at least STARTED. Player loops read it to slow down in the
 * background instead of polling at UI speed for a picture nobody sees.
 */
@Composable
internal fun rememberAppVisible(): State<Boolean> {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember { derivedStateOf { lifecycleState.isAtLeast(Lifecycle.State.STARTED) } }
}
