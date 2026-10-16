package com.ambient.tv.ui.player

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.ambient.tv.ui.common.PlayerPanelNavigation

/** UI lifetime is the fullscreen player, not its current decoder or media item. */
class PlayerChromeState(initialZapOsd: Boolean = false) {
    val visible = mutableStateOf(true)
    /**
     * The OSD was opened by entering live TV or by a channel change. Up/Down keep changing channels until another key
     * is pressed or the OSD hides, so zapping through channels shows each one's info.
     */
    val shownByZap = mutableStateOf(initialZapOsd)
    /** The active engine's OSD and panel content and remote handling; see [PersistentPlayerOverlays]. */
    internal val panelModel = mutableStateOf<PlayerPanelModel?>(null)
    internal val osdModel = mutableStateOf<PlayerOsdSpec?>(null)
    internal val panelKeys = mutableStateOf<((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?>(null)
    val panels = PlayerPanelNavigation()
    internal val focus = PlayerControlFocus()
    val playFocus = FocusRequester()
    val seekFocus = FocusRequester()
    /** The active engine's own focus target, which keeps the remote handling when the OSD hides. */
    internal val engineFocus = FocusRequester()
    /** Whether anything inside the player (engine, OSD, panel) holds the focus. */
    internal var hasFocus = false
}

val LocalPlayerChromeState = compositionLocalOf<PlayerChromeState?> { null }
val LocalPlayerChangingItem = compositionLocalOf { false }

@Composable
fun PersistentPlayerChrome(
    owner: Any,
    changingItem: Boolean = false,
    initialZapOsd: Boolean = false,
    content: @Composable () -> Unit,
) {
    val chrome = remember(owner) { PlayerChromeState(initialZapOsd) }
    CompositionLocalProvider(
        LocalPlayerChromeState provides chrome,
        LocalPlayerControlFocus provides chrome.focus,
        LocalPlayerChangingItem provides changingItem,
    ) {
        LaunchedEffect(chrome) {
            androidx.compose.runtime.snapshotFlow { chrome.visible.value }.collect { if (!it) chrome.shownByZap.value = false }
        }
        Box(Modifier.fillMaxSize()
            .onFocusChanged { chrome.hasFocus = it.hasFocus }
            .onPreviewKeyEvent {
                if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && it.key !in ZAP_KEYS) chrome.shownByZap.value = false
                changingItem && it.key != Key.Back && it.key != Key.MediaStop
            }
            .pointerInput(changingItem) {
                if (changingItem) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content() }
    }
}

@Composable
fun rememberPlayerControlsVisible(): MutableState<Boolean> =
    LocalPlayerChromeState.current?.visible ?: remember { mutableStateOf(true) }

@Composable
fun rememberPlayerPlayFocus(): FocusRequester =
    LocalPlayerChromeState.current?.playFocus ?: remember { FocusRequester() }

/** For the engine's focusable root: see [PlayerChromeState.engineFocus]. */
@Composable
internal fun rememberPlayerEngineFocus(): FocusRequester =
    LocalPlayerChromeState.current?.engineFocus ?: remember { FocusRequester() }

@Composable
fun rememberPlayerSeekFocus(): FocusRequester =
    LocalPlayerChromeState.current?.seekFocus ?: remember { FocusRequester() }

/** Keys that change the live channel while the OSD is hidden or was opened by a channel change. */
private val ZAP_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.ChannelUp, Key.ChannelDown, Key.MediaNext, Key.MediaPrevious)
