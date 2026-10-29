package com.ambient.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import com.ambient.tv.ui.player.LocalPlayerChromeState

/** User activity belongs to the whole panel path, including its separate popup windows. */
internal class PlayerPanelActivity {
    var version by mutableIntStateOf(0)
        private set
    private val pressedPointers = mutableStateListOf<Any>()
    private val blockers = mutableStateListOf<Any>()
    private val focusedPopupWindows = mutableStateListOf<Any>()
    val blocked: Boolean get() = pressedPointers.isNotEmpty() || blockers.isNotEmpty()
    val popupWindowFocused: Boolean get() = focusedPopupWindows.isNotEmpty()

    fun interact() { version++ }
    fun pointerPressed(owner: Any, pressed: Boolean) = pressedPointers.setOwner(owner, pressed)
    fun keepOpen(owner: Any, active: Boolean) = blockers.setOwner(owner, active)
    fun popupFocused(owner: Any, focused: Boolean) = focusedPopupWindows.setOwner(owner, focused)

    private fun MutableList<Any>.setOwner(owner: Any, present: Boolean) {
        if (present) { if (owner !in this) add(owner) } else remove(owner)
    }
}

/** Observe before sliders/clickables consume input; never consume it or count automatic focus. */
internal fun Modifier.playerPanelInput(navigation: PlayerPanelNavigation?): Modifier =
    if (navigation == null) this else composed {
        val owner = remember(navigation) { Any() }
        val activity = navigation.activity
        DisposableEffect(activity, owner) {
            onDispose { activity.pointerPressed(owner, false) }
        }
        onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) activity.interact()
            false
        }.pointerInput(activity, owner) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        activity.pointerPressed(owner, event.changes.any { it.pressed })
                        if (event.changes.any { it.pressed || it.previousPressed || it.scrollDelta != Offset.Zero }) {
                            activity.interact()
                        }
                    }
                }
            } finally {
                activity.pointerPressed(owner, false)
            }
        }
    }

/** A selector is still the panel; losing the main window's focus must not disable its timer. */
@Composable
internal fun ReportPlayerPanelPopupWindow() {
    val activity = LocalPlayerPanelNavigation.current?.activity ?: return
    val owner = remember(activity) { Any() }
    val focused = LocalWindowInfo.current.isWindowFocused
    SideEffect { activity.popupFocused(owner, focused) }
    DisposableEffect(activity, owner) {
        onDispose { activity.popupFocused(owner, false) }
    }
}

/** Dialogs can contain unfinished text or decisions; resume with a fresh deadline on dismissal. */
@Composable
internal fun KeepPlayerPanelOpen() {
    val navigation = LocalPlayerPanelNavigation.current ?: LocalPlayerChromeState.current?.panels
    val activity = navigation?.activity ?: return
    DisposableEffect(activity) {
        val owner = Any()
        activity.keepOpen(owner, true)
        onDispose { activity.keepOpen(owner, false) }
    }
}
