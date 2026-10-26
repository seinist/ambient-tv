package com.ambient.tv.ui.player

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** Lives above channel/renderer replacement, while button registrations live with the OSD. */
internal class PlayerControlFocus {
    private data class Control(val requester: FocusRequester, val owner: Any)
    private val controls = mutableStateMapOf<String, Control>()
    internal var lastKey: String? = null
        private set
    private var lastButtonKey: String? by mutableStateOf(null)
    private var focusedOwner: Any? = null

    val hasFocus: Boolean get() = focusedOwner != null
    /** Observable so the action bar can slide away while the seek bar is focused. */
    var seekFocused: Boolean by mutableStateOf(false)
        private set
    fun target(fallback: FocusRequester): FocusRequester = controls[lastKey]?.requester ?: fallback

    fun buttonTarget(fallback: FocusRequester): FocusRequester = controls[lastButtonKey]?.requester ?: fallback
    fun seekTarget(): FocusRequester? = controls["seek-bar"]?.requester

    /** A closed OSD starts at Play/Pause next time, even during a reversed fade-out. */
    fun resetForNextOpen() {
        lastKey = null
        lastButtonKey = null
        focusedOwner = null
        seekFocused = false
    }

    fun register(key: String, requester: FocusRequester, owner: Any) {
        controls[key] = Control(requester, owner)
    }

    fun unregister(key: String, owner: Any) {
        if (controls[key]?.owner === owner) controls.remove(key)
        if (focusedOwner === owner) {
            focusedOwner = null
            seekFocused = false
        }
    }

    fun focused(key: String, owner: Any, focused: Boolean) {
        if (focused) {
            lastKey = key
            if (key != "seek-bar") lastButtonKey = key
            focusedOwner = owner
            seekFocused = key == "seek-bar"
        } else if (focusedOwner === owner) {
            focusedOwner = null
            seekFocused = false
        }
    }
}

internal val LocalPlayerControlFocus = compositionLocalOf<PlayerControlFocus?> { null }
