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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.ambient.tv.ui.common.PlayerPanelNavigation

/** UI lifetime is the fullscreen player, not its current decoder or media item. */
class PlayerChromeState(initialZapOsd: Boolean = false) {
    internal val osd = PlayerOsdController(initialZapOsd)
    val visible = osd.visible
    /**
     * The OSD was opened by entering live TV or by a channel change. Up/Down keep changing channels until another key
     * is pressed or the OSD hides, so zapping through channels shows each one's info.
     */
    val shownByZap = osd.shownByZap
    /** The active engine's OSD and panel content and remote handling; see [PersistentPlayerOverlays]. */
    internal val panelModel = mutableStateOf<PlayerPanelModel?>(null)
    internal val osdModel = mutableStateOf<PlayerOsdSpec?>(null)
    /** VOD time readouts survive a decoder replacement, but never a media/version change. */
    internal val timelineDuration = mutableStateOf(PlayerTimelineDuration())
    /** What the details dialog shows; published by the screen, so a renderer switch keeps it. */
    internal val detail = mutableStateOf<PlayerDetailInfo?>(null)
    /** Whether the Details button is switched on for this player; Info only opens the dialog then. */
    internal val detailEnabled = mutableStateOf(false)
    internal val panelKeys = mutableStateOf<((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?>(null)
    val panels = PlayerPanelNavigation()
    internal val focus = PlayerControlFocus()
    val playFocus = FocusRequester()
    val seekFocus = FocusRequester()
    /** The active engine's own focus target, which keeps the remote handling when the OSD hides. */
    internal val engineFocus = FocusRequester()
    /** Whether anything inside the player (engine, OSD, panel) holds the focus. */
    internal var hasFocus = false
    /** Last title that was ready in this player; see [titleChangeRevealsOsd]. */
    internal var readyTitleIdentity: String? = null

    /** Synchronous: no panel-close effect may reveal controls between these changes. */
    internal fun closePanelsForInactivity(expectedActivity: Int): Boolean {
        if (!panels.closeForInactivity(expectedActivity)) return false
        osd.hideImmediately()
        return true
    }
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
        PlayerPanelAutoHide(chrome, changingItem)
        ProgressiveSeekTicker(chrome.osd.progressiveSeek)
        androidx.compose.runtime.DisposableEffect(chrome) {
            onDispose { chrome.osd.progressiveSeek.cancel() }
        }
        // Info opens the details dialog when its action-bar button is switched on.
        val detailsLabel = androidx.compose.ui.res.stringResource(com.ambient.tv.R.string.player_details)
        PlayerKeyActions(chrome, detailsLabel)
        LaunchedEffect(chrome) {
            androidx.compose.runtime.snapshotFlow { chrome.visible.value }.collect { if (!it) chrome.shownByZap.value = false }
        }
        Box(Modifier.fillMaxSize()
            .onFocusChanged { chrome.hasFocus = it.hasFocus }
            .onPreviewKeyEvent {
                // Shared by every renderer, including when the OSD or side panel owns focus.
                val session = chrome.panelModel.value?.audioSession
                if (it.key == Key.Info) {
                    val detail = chrome.detail.value
                    if (detail != null && chrome.detailEnabled.value) {
                        if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            it.nativeKeyEvent.repeatCount == 0) toggleDetailsByInfoKey(chrome.panels, detailsLabel)
                        return@onPreviewKeyEvent true
                    }
                }
                if (it.key == Key.O && !it.isCtrlPressed && !it.isAltPressed &&
                    !it.isMetaPressed && !it.isShiftPressed && session != null) {
                    if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        it.nativeKeyEvent.repeatCount == 0) {
                        session.performanceHint = !session.performanceHint
                    }
                    return@onPreviewKeyEvent true
                }
                // OK must see the zap flag before claiming the compact controls; otherwise
                // that first press would reach Play/Pause on the timeline/info target.
                if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    it.key !in ZAP_KEYS && it.key !in CONFIRM_KEYS) chrome.shownByZap.value = false
                changingItem && it.key != Key.Back && it.key != Key.MediaStop
            }
            .pointerInput(changingItem) {
                if (changingItem) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content() }
    }
}

/**
 * Player actions of user key bindings (Settings → Keys & remote), shared by every player.
 * A binding is its own opt-in: none of these may depend on which action-bar buttons are shown.
 */
@Composable
private fun PlayerKeyActions(chrome: PlayerChromeState, detailsLabel: String) {
    fun openPanel(panel: com.ambient.tv.ui.common.UnifiedPlayerPanel): Boolean {
        if (chrome.panelModel.value == null) return false
        chrome.panels.open(panel)
        return true
    }
    fun seek(forward: Boolean): Boolean {
        val snapshot = BackgroundPlaybackSession.snapshot.value
        if (!snapshot.active || snapshot.metadata?.isLive == true) return false
        val step = com.ambient.tv.ui.keyboard.KeyBindings.seekStepMs()
        BackgroundPlaybackSession.userSeek()
        BackgroundPlaybackSession.seekTo(com.ambient.tv.ui.keyboard.kodiSeekTarget(
            snapshot.status.positionMs, snapshot.status.durationMs, if (forward) step else -step,
        ))
        chrome.osd.showSeekFeedback()
        return true
    }
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.SHOW_OSD) { chrome.osd.reveal(); true }
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.PLAYBACK_SETTINGS) {
        openPanel(com.ambient.tv.ui.common.UnifiedPlayerPanel.SETTINGS)
    }
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.SUBTITLES) {
        openPanel(com.ambient.tv.ui.common.UnifiedPlayerPanel.SUBTITLES)
    }
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.AUDIO_TRACKS) {
        openPanel(com.ambient.tv.ui.common.UnifiedPlayerPanel.AUDIO)
    }
    // A key the user bound to Details is its own opt-in: it works whether or not the optional
    // Details button is shown in the action bar (only the Info key follows that switch).
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.DETAILS) {
        if (chrome.detail.value == null) false
        else { toggleDetailsByInfoKey(chrome.panels, detailsLabel); true }
    }
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.SEEK_BACK) { seek(forward = false) }
    com.ambient.tv.ui.keyboard.RegisterKeyAction(com.ambient.tv.ui.keyboard.KeyAction.SEEK_FORWARD) { seek(forward = true) }
}

/** Info opens the details (recorded as opened from the Details button) or closes them again. */
internal fun toggleDetailsByInfoKey(navigation: PlayerPanelNavigation, detailsLabel: String) {
    if (navigation.panel == com.ambient.tv.ui.common.UnifiedPlayerPanel.DETAILS) closePlayerDetails(navigation)
    else openPlayerDetails(navigation, detailsLabel)
}

@Composable
fun rememberPlayerControlsVisible(): MutableState<Boolean> {
    val state = LocalPlayerChromeState.current?.visible ?: remember { mutableStateOf(true) }
    // OK extends the sleep pre-warning only while the player's controls are hidden.
    SleepWarningKeyGate.ReportPlayerChrome(state.value)
    return state
}

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
private val CONFIRM_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)
