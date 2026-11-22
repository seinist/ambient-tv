package com.ambient.tv.ui.gamepad

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.display.UiRenderInputRoot
import com.ambient.tv.ui.display.findUiRenderWindow
import com.ambient.tv.ui.keyboard.KeyAction

/** Dialogs own a separate Android/embedded input root, so they cannot rely on MainActivity. */
internal class GameDialogInputHandler(private val dispatchTranslatedKey: (KeyEvent) -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private var forwarding = false
    private var disposed = false
    private val host = object : GamepadHost {
        override val playerForeground = false
        override val gameInputMode: GameInputMode?
            get() = when (GameInputRouting.mode) {
                null -> null
                GameInputMode.MENU, GameInputMode.DIALOG -> GameInputMode.DIALOG
                else -> GameInputMode.BLOCKED
            }
        override val gameInputGeneration get() = GameInputRouting.generation
        override val gameInputTransitions get() = GameInputRouting.transitions
        override val recordingKey get() = com.ambient.tv.ui.keyboard.KeyBindings.capture != null
        override fun now() = SystemClock.uptimeMillis()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val runnable = Runnable(task)
            handler.postDelayed(runnable, delayMs)
            return { handler.removeCallbacks(runnable) }
        }
        override fun emitKey(event: KeyEvent) {
            if (disposed || !gameInputMode.isGameMenu) return
            if (GameInputRouting.handleKey(event)) return
            forwarding = true
            try { dispatchTranslatedKey(event) } finally { forwarding = false }
        }
        override fun perform(action: KeyAction) { GameInputRouting.perform(action) }
        // Game-menu commands contain no volume actions; real volume keys go to the window.
        override fun volume(command: GamepadVolumeCommand) = Unit
    }
    private val controller = GamepadController(host, rawSink = { null })
    private val observer = GameInputRouting.observeChanges { controller.notifyGameInputChanged() }

    fun key(event: KeyEvent, dispatch: () -> Boolean): Boolean {
        if (disposed || forwarding || !GameInputRouting.active) return dispatch()
        if (controller.handleKey(event)) return true
        com.ambient.tv.ui.keyboard.KeyBindings.capture?.let { if (it(event)) return true }
        if (GameInputRouting.handleKey(event)) return true
        return dispatch()
    }

    fun motion(event: MotionEvent, dispatch: () -> Boolean): Boolean =
        if (!disposed && GameInputRouting.active && controller.handleMotion(event)) true else dispatch()

    fun focusLost() = controller.releaseAll()
    fun dispose() {
        disposed = true
        GameInputRouting.removeObserver(observer)
        controller.releaseAll()
    }
}

private data class GameDialogInputRegistration(val input: GameDialogInputHandler, val callback: Window.Callback)
private val gameDialogInputs = mutableMapOf<Window, GameDialogInputRegistration>()

/** Embedded windows use the same bridge before their own Back/text-input dispatch. */
internal fun dispatchGameDialogKey(window: Window, event: KeyEvent, dispatch: () -> Boolean): Boolean =
    gameDialogInputs[window]?.input?.key(event, dispatch) ?: dispatch()

internal fun dispatchGameDialogMotion(window: Window, event: MotionEvent, dispatch: () -> Boolean): Boolean =
    gameDialogInputs[window]?.input?.motion(event, dispatch) ?: dispatch()

/** Preserve the dialog's Back dispatcher owner when its Window.Callback is wrapped. */
internal fun gameDialogWindowCallback(window: Window): Window.Callback? =
    gameDialogInputs[window]?.callback ?: window.callback

/** Installed once for a focusable dialog; inactive outside games, restored when it is removed. */
@Composable
internal fun GameDialogInputSurface() {
    if (!GameInputRouting.active) return
    val view = LocalView.current
    val window = remember(view) { view.findUiRenderWindow() }
    DisposableEffect(window, view) {
        val delegate = window?.callback
        if (window == null || delegate == null) return@DisposableEffect onDispose { }
        val input = GameDialogInputHandler { event ->
            // Re-evaluate ancestry: a render-resolution fallback can move this same ComposeView.
            val embedded = generateSequence(view) { it.parent as? View }
                .filterIsInstance<UiRenderInputRoot>().firstOrNull()
                ?.takeIf { it.isAttachedToWindow && it.isShown }
            embedded?.dispatchKeyEvent(event) ?: delegate.dispatchKeyEvent(event)
        }
        val bridge = object : Window.Callback by delegate {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                input.key(event) { delegate.dispatchKeyEvent(event) }
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
                input.motion(event) { delegate.dispatchGenericMotionEvent(event) }
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) input.focusLost()
                delegate.onWindowFocusChanged(hasFocus)
            }
        }
        gameDialogInputs[window] = GameDialogInputRegistration(input, delegate)
        window.callback = bridge
        onDispose {
            input.dispose()
            if (gameDialogInputs[window]?.input === input) gameDialogInputs.remove(window)
            if (window.callback === bridge) window.callback = delegate
        }
    }
}
