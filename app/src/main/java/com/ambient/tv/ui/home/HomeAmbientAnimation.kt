package com.ambient.tv.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.data.browse.BrowseWorkBudget
import kotlinx.coroutines.delay

/** Decorative motion yields to navigation without changing the focus/scroll animation clock. */
@Composable
internal fun rememberIdleAmbientPhase(durationMillis: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(durationMillis, owner) {
        val durationNs = durationMillis.toLong().coerceAtLeast(1) * 1_000_000L
        var elapsedNs = 0L
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previousNs: Long? = null
            while (true) {
                if (BrowseWorkBudget.recentlyInteracting()) {
                    previousNs = null
                    delay(100)
                    continue
                }
                val now = withFrameNanos { it }
                // Input can arrive while waiting for this frame.
                if (BrowseWorkBudget.recentlyInteracting()) {
                    previousNs = null
                    continue
                }
                previousNs?.let { elapsedNs = (elapsedNs + (now - it).coerceAtLeast(0)) % (2 * durationNs) }
                previousNs = now
                val progress = if (elapsedNs <= durationNs) elapsedNs.toFloat() / durationNs
                    else (2 * durationNs - elapsedNs).toFloat() / durationNs
                phase.floatValue = FastOutSlowInEasing.transform(progress)
            }
        }
    }
    return phase
}
