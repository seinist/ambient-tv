package com.ambient.tv.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Independent owners prevent releasing one finger/key from releasing another held input. */
internal class HoldActionState(private val changed: (Boolean) -> Unit) {
    private val owners = mutableSetOf<Any>()
    fun set(owner: Any, down: Boolean) {
        val wasHeld = owners.isNotEmpty()
        if (down) owners.add(owner) else owners.remove(owner)
        if (wasHeld != owners.isNotEmpty()) changed(owners.isNotEmpty())
    }
    fun releaseAll() {
        if (owners.isEmpty()) return
        owners.clear()
        changed(false)
    }
}

/**
 * A real hold, with cancellation on lost focus/window/lifecycle and disposal. The modifier
 * consumes its own pointer stream so releasing a game tool cannot also open the player OSD.
 * Accessibility activation performs a bounded hold; it never leaves a tool latched on.
 */
@Composable
internal fun Modifier.holdAction(
    enabled: Boolean = true,
    onHold: (Boolean) -> Unit,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val latest = rememberUpdatedState(onHold)
    val scope = rememberCoroutineScope()
    val keyOwners = remember { mutableMapOf<Pair<Int, Int>, Any>() }
    val state = remember(interactionSource) {
        var press: PressInteraction.Press? = null
        HoldActionState { held ->
            if (held) {
                press = PressInteraction.Press(Offset.Zero).also { interactionSource?.tryEmit(it) }
            } else {
                press?.let { interactionSource?.tryEmit(PressInteraction.Release(it)) }
                press = null
            }
            latest.value(held)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    fun releaseKeys() {
        keyOwners.values.forEach { state.set(it, false) }
        keyOwners.clear()
    }
    LaunchedEffect(enabled, window.isWindowFocused) {
        if (!enabled || !window.isWindowFocused) { releaseKeys(); state.releaseAll() }
    }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                releaseKeys(); state.releaseAll()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); releaseKeys(); state.releaseAll() }
    }
    return onFocusChanged { if (!it.hasFocus) releaseKeys() }
        .onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionCenter && event.key != Key.Enter &&
                event.key != Key.NumPadEnter && event.key != Key.Spacebar) false
            else {
                val native = event.nativeKeyEvent
                val identity = native.deviceId to native.keyCode
                if (enabled && event.type == KeyEventType.KeyDown && native.repeatCount == 0 && !native.isCanceled) {
                    state.set(keyOwners.getOrPut(identity) { Any() }, true)
                }
                if (event.type == KeyEventType.KeyUp) keyOwners.remove(identity)?.let { state.set(it, false) }
                true
            }
        }
        .pointerInput(state, enabled) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                val owner = Any()
                if (enabled) state.set(owner, true)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        pointer.consume()
                        val inside = pointer.position.x in 0f..size.width.toFloat() &&
                            pointer.position.y in 0f..size.height.toFloat()
                        if (!inside) state.set(owner, false)
                    } while (pointer.pressed)
                } finally { state.set(owner, false) }
            }
        }
        // A hold is an independent button, including inside the player's clickable surface.
        // Preserve its label/action as a child boundary instead of merging into the OSD toggle.
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (enabled) onClick {
                scope.launch {
                    val owner = Any()
                    state.set(owner, true)
                    try { delay(300) } finally { state.set(owner, false) }
                }
                true
            }
        }
        .focusable(enabled = enabled, interactionSource = interactionSource)
}
