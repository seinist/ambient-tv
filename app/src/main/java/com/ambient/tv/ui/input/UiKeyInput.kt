package com.ambient.tv.ui.input

import android.view.KeyEvent
import android.view.View

/**
 * In-process controller/companion keys bypass ViewRoot's input-mode transition. Establish
 * keyboard navigation on the receiving root before Compose searches for a focus target.
 * Releases, soft-keyboard input, text and system keys do not change the UI input mode.
 */
internal fun View.prepareForUiKeyEvent(event: KeyEvent) {
    if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled ||
        event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return
    when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE ->
            requestUiKeyInputMode()
    }
}

/** Also used when a physical game-menu shortcut is consumed before it becomes a UI key. */
internal fun View.requestUiKeyInputMode() {
    // Keep an editor/ComposeView's focus, and never move focus again for held/repeated keys.
    if (isInTouchMode) (findFocus() ?: this).requestFocusFromTouch()
}
