package com.ambient.tv.ui.common

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One scroll mutation per gesture; remote repeat events never queue animations. */
internal class ScrollbarKeyScroller(
    private val scope: CoroutineScope,
    private val state: ScrollableState,
    private val viewportSize: () -> Float,
    private val reverseLayout: () -> Boolean,
) {
    private class Gesture(val key: Key) {
        var held = true
        var continuous = false
    }

    private var gesture: Gesture? = null
    private var job: Job? = null

    fun stop() {
        gesture?.held = false
        gesture = null
        job?.cancel()
        job = null
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.key) {
            Key.DirectionUp, Key.PageUp -> -1
            Key.DirectionDown, Key.PageDown -> 1
            else -> return false
        }
        if (event.type == KeyEventType.KeyUp) {
            gesture?.takeIf { it.key == event.key }?.let {
                it.held = false
                // A quick tap still completes its small step, even with Down/Up in one
                // frame. Once holding has taken over, release stops without a tail.
                if (it.continuous) stop()
            }
            return true
        }
        if (event.type != KeyEventType.KeyDown) return true

        val page = event.key == Key.PageUp || event.key == Key.PageDown
        if (gesture?.key == event.key && gesture?.held == true && job?.isActive == true) {
            return true
        }
        stop()
        val current = Gesture(event.key)
        gesture = current
        job = scope.launch {
            state.scroll(MutatePriority.UserInput) {
                val sign = direction * if (reverseLayout()) -1 else 1
                val viewport = viewportSize().coerceAtLeast(1f)
                val distance = viewport * if (page) 1f else 0.15f
                val animation = AnimationState(initialValue = 0f)
                var previous = 0f
                var lastFrame = Long.MIN_VALUE
                // The small linear step runs at one viewport/second, matching the
                // subsequent hold instead of braking between repeated page animations.
                animation.animateTo(
                    targetValue = distance,
                    animationSpec = if (page) tween(300) else tween(150, easing = LinearEasing),
                ) {
                    scrollBy(sign * (value - previous))
                    previous = value
                    lastFrame = lastFrameTimeNanos
                }
                if (page || !current.held) return@scroll

                current.continuous = true
                while (current.held) {
                    val now = withFrameNanos { it }
                    // A delayed frame must not become a large catch-up jump.
                    val seconds = (now - lastFrame).coerceIn(0L, 50_000_000L) / 1_000_000_000f
                    lastFrame = now
                    scrollBy(sign * viewportSize().coerceAtLeast(1f) * seconds)
                    // Stay responsive at a boundary: a paginated list may append rows
                    // while the key is still held. The next frame can then continue.
                }
            }
        }
        return true
    }
}
