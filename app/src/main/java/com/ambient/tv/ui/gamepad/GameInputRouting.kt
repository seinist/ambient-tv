package com.ambient.tv.ui.gamepad

import android.view.KeyEvent
import com.ambient.tv.ui.keyboard.KeyAction

/** Games have an input lifetime independent of background audio/video playback. */
enum class GameInputMode { RUNNING, MENU, DIALOG, BLOCKED }

/**
 * Owned by the visible game session, on the main thread. The owner identity prevents disposal of
 * an old native session from clearing a replacement session's input policy. A context revision
 * invalidates pending long presses and held menu navigation before they can enter another mode
 * or menu page. [attach] and [update] compare the optional page context by value, so callers can
 * pass a small immutable navigation key without invalidating input on ordinary recomposition.
 */
object GameInputRouting {
    private data class Session(
        val owner: Any,
        val mode: GameInputMode,
        val onMenu: () -> Unit,
        val onSettings: () -> Unit,
        val onInfo: () -> Unit,
        val context: Any?,
    )

    @Volatile private var session: Session? = null
    private val observers = linkedMapOf<Any, () -> Unit>()
    val transitions = GameInputTransitions()
    val active: Boolean get() = session != null
    private val _gameStartEpoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val gameStartEpoch: kotlinx.coroutines.flow.StateFlow<Long> = _gameStartEpoch
    val mode: GameInputMode? get() = session?.mode
    var generation: Long = 0
        private set

    fun attach(
        owner: Any,
        mode: GameInputMode,
        onMenu: () -> Unit,
        onSettings: () -> Unit = {},
        onInfo: () -> Unit = {},
        context: Any? = null,
    ) {
        val old = session
        session = Session(owner, mode, onMenu, onSettings, onInfo, context)
        if (old == null) {
            _gameStartEpoch.value += 1
            com.ambient.tv.data.browse.BrowseWorkBudget.speculativeJob?.cancel()
        }
        if (old?.owner !== owner || old.mode != mode || old.context != context) changed()
    }

    fun update(owner: Any, mode: GameInputMode, context: Any? = null) {
        val old = session?.takeIf { it.owner === owner } ?: return
        if (old.mode == mode && old.context == context) return
        session = old.copy(mode = mode, context = context)
        changed()
    }

    fun detach(owner: Any) {
        if (session?.owner !== owner) return
        session = null
        changed()
    }

    fun observeChanges(observer: () -> Unit): Any = Any().also { observers[it] = observer }
    fun removeObserver(token: Any) { observers.remove(token) }

    private fun changed() {
        generation++
        transitions.advance(generation, active)
        observers.values.toList().forEach { it() }
    }

    /** Bound app actions must never fall through to the library while a game owns the screen. */
    fun perform(action: KeyAction): Boolean {
        val current = session ?: return false
        if (current.mode == GameInputMode.BLOCKED) return true
        when (action) {
            KeyAction.SHOW_OSD, KeyAction.PLAY_PAUSE, KeyAction.OPEN_PLAYER,
            KeyAction.SIDEBAR -> current.onMenu()
            KeyAction.SETTINGS, KeyAction.PLAYBACK_SETTINGS -> current.onSettings()
            KeyAction.DETAILS -> current.onInfo()
            else -> Unit
        }
        return true
    }

    /** Unbound menu/media keys keep game semantics; OS Home, volume and power stay untouched. */
    fun handleKey(event: KeyEvent): Boolean {
        val current = session ?: return false
        val toggle = when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
            KeyEvent.KEYCODE_MEDIA_PLAY -> current.mode != GameInputMode.RUNNING
            KeyEvent.KEYCODE_MEDIA_PAUSE -> current.mode == GameInputMode.RUNNING
            // Never let a media stop key fall through to an unrelated background media session.
            KeyEvent.KEYCODE_MEDIA_STOP -> false
            else -> return false
        }
        if (toggle && current.mode != GameInputMode.BLOCKED &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
            current.onMenu()
        }
        return true
    }
}

internal val GameInputMode?.isGameMenu: Boolean
    get() = this == GameInputMode.MENU || this == GameInputMode.DIALOG

/** Menu actions intentionally ignore app-wide shortcuts, retaining the user's confirm side. */
internal fun gameMenuCommand(button: GamepadButton, long: Boolean, confirmOnB: Boolean): GamepadCommand {
    if (long) return GamepadCommand.None
    val command = when (button) {
        GamepadButton.A -> if (confirmOnB) GamepadKeyCommand.BACK else GamepadKeyCommand.CONFIRM
        GamepadButton.B -> if (confirmOnB) GamepadKeyCommand.CONFIRM else GamepadKeyCommand.BACK
        GamepadButton.MODE, GamepadButton.START -> GamepadKeyCommand.CONTEXT_MENU
        GamepadButton.SELECT -> GamepadKeyCommand.BACK
        GamepadButton.L1 -> GamepadKeyCommand.PAGE_UP
        GamepadButton.R1 -> GamepadKeyCommand.PAGE_DOWN
        else -> return GamepadCommand.None
    }
    return GamepadCommand.Key(command)
}

/** System-owned keys must not be held or swallowed at a game/menu boundary. */
private val SYSTEM_KEYS = setOf(
    KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
    KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_ASSIST,
    KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SETTINGS,
    KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_ALL_APPS,
)

internal fun gameTracksKey(code: Int): Boolean = code !in SYSTEM_KEYS
