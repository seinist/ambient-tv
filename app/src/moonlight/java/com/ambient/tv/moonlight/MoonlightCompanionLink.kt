package com.ambient.tv.moonlight

import com.ambient.tv.companion.CompanionGamepadSession
import com.ambient.tv.companion.CompanionGamepadInteraction
import javax.inject.Inject

/**
 * Activity-owned bridge to the existing authenticated Companion TLS gamepad protocol.
 * Reuses its deadman, context tickets, ports and input-release guarantees.
 */
class MoonlightCompanionLink @Inject constructor(private val engine: MoonlightStreamEngine) {
    private var sink: MoonlightCompanionSink? = null

    fun attach(title: String, usedPorts: () -> Set<Int>, onMenu: () -> Unit) {
        detach()
        val current = MoonlightCompanionSink(engine, usedPorts)
        sink = current
        CompanionGamepadSession.attachMoonlight(current, title, onMenu = onMenu)
    }

    fun interaction(playing: Boolean, menu: Boolean) {
        val current = sink ?: return
        val state = when {
            menu -> CompanionGamepadInteraction.MENU
            playing -> CompanionGamepadInteraction.PLAYING
            else -> CompanionGamepadInteraction.LOADING
        }
        CompanionGamepadSession.setMoonlightInteraction(current, state)
    }

    fun reservedPorts(): Set<Int> = engine.remoteReservedPorts()

    fun detach() {
        val previous = sink ?: return
        sink = null
        CompanionGamepadSession.detachMoonlight(previous)
        previous.releaseEverything()
    }
}
