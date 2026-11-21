package com.ambient.tv.companion

import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import kotlinx.serialization.Serializable
import java.util.UUID

/** The layout selection is advisory; the TV remains the authority for the active core. */
@Serializable
data class CompanionGamepadInfo(
    val sessionId: String = "",
    val systemId: String = "",
    val coreId: String = "",
    val title: String = "",
    val pointerInput: Boolean = false,
    val ready: Boolean = false,
)

/** Whole RetroPad snapshot, not click events. Superseded snapshots may safely be coalesced. */
@Serializable
data class CompanionGamepadState(
    val buttons: Int = 0,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
) {
    fun validate() {
        require(buttons in 0..0xffff)
        require(listOf(leftX, leftY, rightX, rightY).all { it.isFinite() && it in -1f..1f })
    }
}

/**
 * Main-thread bridge between authenticated Companion TLS sessions and the active Libretro core.
 * Independent of TV remote-control leases: up to four paired phones can play together.
 * All entry points run on the main dispatcher (including release on socket teardown).
 */
object CompanionGamepadSession {
    private class Active(
        val input: RetroPadInput,
        val info: CompanionGamepadInfo,
        val onMenu: () -> Unit,
        var ready: Boolean = false,
    )

    private var active: Active? = null
    private val ports = LinkedHashMap<String, Int>()

    fun attach(input: RetroPadInput, systemId: String, coreId: String, title: String,
               pointerInput: Boolean, onMenu: () -> Unit) {
        if (active?.input === input) return
        detachCurrent()
        active = Active(input, CompanionGamepadInfo(
            sessionId = UUID.randomUUID().toString(),
            systemId = systemId.take(40), coreId = coreId.take(80),
            title = title.take(120), pointerInput = pointerInput,
        ), onMenu)
    }

    fun setReady(input: RetroPadInput, ready: Boolean) {
        val game = active?.takeIf { it.input === input } ?: return
        if (game.ready == ready) return
        game.ready = ready
        if (!ready) ports.values.forEach { game.input.remoteState(it, 0, 0f, 0f, 0f, 0f) }
    }

    fun info(): CompanionGamepadInfo? = active?.let { it.info.copy(ready = it.ready) }
    fun port(session: String): Int = ports[session] ?: -1

    /** The assigned port is stable for this socket; prefer ports unused by local devices. */
    fun claim(session: String): Int {
        val game = active ?: return -1
        ports[session]?.let { return it }
        val occupied = ports.values.toSet() + game.input.localUsedPorts()
        val port = (0 until RetroPorts.COUNT).firstOrNull { it !in occupied } ?: return -1
        game.input.reserveRemotePort(port, true)
        ports[session] = port
        return port
    }

    fun input(session: String, packet: CompanionGamepadState): Boolean {
        packet.validate()
        val game = active ?: return false
        val port = ports[session] ?: return false
        if (!game.ready) {
            game.input.remoteState(port, 0, 0f, 0f, 0f, 0f)
            return false
        }
        game.input.remoteState(port, packet.buttons, packet.leftX, packet.leftY, packet.rightX, packet.rightY)
        return true
    }

    fun menu(session: String): Boolean {
        val game = active ?: return false
        if (session !in ports) return false
        game.onMenu()
        return true
    }

    fun release(session: String) {
        val port = ports.remove(session) ?: return
        active?.input?.reserveRemotePort(port, false)
    }

    fun detach(input: RetroPadInput) {
        if (active?.input === input) detachCurrent()
    }

    private fun detachCurrent() {
        val game = active ?: return
        ports.values.forEach { game.input.reserveRemotePort(it, false) }
        ports.clear()
        active = null
    }
}
