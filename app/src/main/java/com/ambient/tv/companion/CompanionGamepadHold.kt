package com.ambient.tv.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Touch and physical input share a hold without releasing each other; its ticket never changes. */
internal class CompanionGamepadHold(
    private val rewind: Boolean,
    private val send: (CompanionGamepadCommand) -> Boolean,
) {
    private val owners = mutableSetOf<Any>()
    private var ticket: CompanionGamepadCommand? = null
    var held by mutableStateOf(false)
        private set

    fun set(owner: Any, pressed: Boolean, info: CompanionGamepadInfo?, cancelled: Boolean = false) {
        if (!pressed) {
            owners.remove(owner)
            if (owners.isEmpty()) clear(cancelled)
            return
        }
        if (owner in owners) return
        if (info == null || if (rewind) !info.acceptsGameplay || !info.rewindAvailable else !info.acceptsNavigation) return
        val previous = ticket
        if (previous != null && (previous.sessionId != info.sessionId || previous.contextRevision != info.contextRevision)) clear(true)
        if (ticket == null) {
            val command = CompanionGamepadCommand(info.sessionId, info.contextRevision,
                if (rewind) "rewind" else "ok_down", pressed = rewind)
            if (!send(command)) return
            ticket = command
        }
        owners += owner
        held = true
    }

    /** A rejected renewal is a lost context/connection, not a request to acquire a fresh ticket. */
    fun renew() {
        val original = ticket ?: return
        if (!send(if (rewind) original else original.copy(action = "ok_renew"))) clear(true)
    }

    fun clear(cancelled: Boolean = true) {
        val original = ticket
        ticket = null
        owners.clear()
        held = false
        if (original != null) send(original.copy(action = if (rewind) "rewind" else if (cancelled) "ok_cancel" else "ok_up", pressed = false))
    }
}
