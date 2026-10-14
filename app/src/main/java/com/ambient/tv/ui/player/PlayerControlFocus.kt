package com.ambient.tv.ui.player

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/** Lives above channel/renderer replacement, while button registrations live with the OSD. */
internal class PlayerControlFocus {
    private data class Control(val requester: FocusRequester, val owner: Any)
    private val controls = mutableMapOf<String, Control>()
    internal var lastKey: String? = null
        private set
    private var focusedOwner: Any? = null

    val hasFocus: Boolean get() = focusedOwner != null
    fun target(fallback: FocusRequester): FocusRequester = controls[lastKey]?.requester ?: fallback

    fun register(key: String, requester: FocusRequester, owner: Any) {
        controls[key] = Control(requester, owner)
    }

    fun unregister(key: String, owner: Any) {
        if (controls[key]?.owner === owner) controls.remove(key)
        if (focusedOwner === owner) focusedOwner = null
    }

    fun focused(key: String, owner: Any, focused: Boolean) {
        if (focused) {
            lastKey = key
            focusedOwner = owner
        } else if (focusedOwner === owner) focusedOwner = null
    }
}

internal val LocalPlayerControlFocus = compositionLocalOf<PlayerControlFocus?> { null }
