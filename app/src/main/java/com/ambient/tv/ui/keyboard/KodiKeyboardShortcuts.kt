package com.ambient.tv.ui.keyboard

import android.view.KeyEvent

/** Familiar physical-keyboard shortcuts matching Kodi's core navigation/playback keymap. */
internal enum class KodiKeyboardAction(
    val requiresPlayback: Boolean,
    val repeatable: Boolean = false,
) {
    BACK(requiresPlayback = false),
    TOGGLE_PLAYBACK(requiresPlayback = true),
    PLAY(requiresPlayback = true),
    STOP(requiresPlayback = true),
    SEEK_BACKWARD(requiresPlayback = true, repeatable = true),
    SEEK_FORWARD(requiresPlayback = true, repeatable = true),
    VOLUME_DOWN(requiresPlayback = false, repeatable = true),
    VOLUME_UP(requiresPlayback = false, repeatable = true),
    TOGGLE_MUTE(requiresPlayback = false),
}

/**
 * Resolve only unmodified shortcuts. Modified letters remain available to text fields and to
 * Android/Compose shortcuts such as Ctrl+V. MainActivity asks Compose first, so an active editor
 * also keeps unmodified letters, Space and Backspace.
 */
internal fun kodiKeyboardAction(
    keyCode: Int,
    ctrlPressed: Boolean = false,
    altPressed: Boolean = false,
    shiftPressed: Boolean = false,
    metaPressed: Boolean = false,
): KodiKeyboardAction? {
    if (ctrlPressed || altPressed || metaPressed) return null
    val action = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_DEL,
        -> KodiKeyboardAction.BACK

        KeyEvent.KEYCODE_SPACE -> KodiKeyboardAction.TOGGLE_PLAYBACK
        KeyEvent.KEYCODE_P -> KodiKeyboardAction.PLAY
        KeyEvent.KEYCODE_X -> KodiKeyboardAction.STOP
        KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_COMMA,
        KeyEvent.KEYCODE_APOSTROPHE,
        -> KodiKeyboardAction.SEEK_BACKWARD

        KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_PERIOD,
        -> KodiKeyboardAction.SEEK_FORWARD

        KeyEvent.KEYCODE_MINUS,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
        -> KodiKeyboardAction.VOLUME_DOWN

        KeyEvent.KEYCODE_PLUS,
        KeyEvent.KEYCODE_EQUALS,
        KeyEvent.KEYCODE_NUMPAD_ADD,
        -> KodiKeyboardAction.VOLUME_UP

        KeyEvent.KEYCODE_F8 -> KodiKeyboardAction.TOGGLE_MUTE
        else -> null
    }
    // Shift+letter is Kodi's list jump and shifted punctuation is text, while + commonly needs
    // Shift depending on the physical keyboard layout.
    return if (shiftPressed && keyCode in setOf(
            KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_APOSTROPHE,
        )) null else action
}

internal fun kodiSeekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val current = positionMs.coerceAtLeast(0L)
    val upperBound = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    return if (deltaMs >= 0L) {
        current.coerceAtMost(upperBound - deltaMs.coerceAtMost(upperBound)) + deltaMs
    } else {
        (current + deltaMs).coerceAtLeast(0L)
    }.coerceAtMost(upperBound)
}
