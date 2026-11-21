package com.ambient.tv.companion

import android.os.Handler
import android.os.Looper
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

/** Up to four real contacts with stable indices, in normalized core-frame coordinates. */
@Serializable
data class CompanionPointerContact(
    val index: Int, val x: Float, val y: Float, val pressed: Boolean = true,
) {
    fun validate() {
        require(index in 0..3 && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
    }
}

/**
 * Touch (absolute multi-contact), mouse (relative deltas) or lightgun (absolute aim).
 * A lightgun reload uses bit 1 and the off-screen flag; bit 0 is its trigger.
 */
@Serializable
data class CompanionPointerState(
    val kind: String = "touch",
    val contacts: List<CompanionPointerContact> = emptyList(),
    val dx: Float = 0f, val dy: Float = 0f,
    val buttons: Int = 0, val offscreen: Boolean = false,
) {
    fun validate() {
        require(kind in setOf("touch", "mouse", "gun"))
        require(contacts.size <= 4 && contacts.map { it.index }.distinct().size == contacts.size)
        contacts.forEach { it.validate() }
        require(dx.isFinite() && dy.isFinite() && dx in -1f..1f && dy in -1f..1f)
        require(buttons in 0..15)
    }
}

/** libretro sensor units: accelerometer in g (gravity included), gyro in rad/s. */
@Serializable
data class CompanionMotionState(
    val mask: Int = 0,
    val ax: Float = 0f, val ay: Float = 0f, val az: Float = 0f,
    val gx: Float = 0f, val gy: Float = 0f, val gz: Float = 0f,
) {
    fun validate() {
        require(mask in 0..3)
        require(listOf(ax, ay, az).all { it.isFinite() && it in -16f..16f })
        require(listOf(gx, gy, gz).all { it.isFinite() && it in -100f..100f })
    }
}

/** Whole RetroPad snapshot, including optional core-specialized input. */
@Serializable
data class CompanionGamepadState(
    val buttons: Int = 0,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val pointer: CompanionPointerState? = null,
    val motion: CompanionMotionState? = null,
) {
    fun validate() {
        require(buttons in 0..0xffff)
        require(listOf(leftX, leftY, rightX, rightY).all { it.isFinite() && it in -1f..1f })
        pointer?.validate()
        motion?.validate()
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
        val onPointer: (Int, CompanionPointerState?) -> Unit,
        val onMotion: (Int, CompanionMotionState?) -> Unit,
        var ready: Boolean = false,
    )

    private var active: Active? = null
    private val ports = LinkedHashMap<String, Int>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val deadman = LinkedHashMap<String, Runnable>()
    private val previousPointer = mutableMapOf<Int, CompanionPointerState>()
    private val previousMotion = mutableMapOf<Int, CompanionMotionState>()

    private fun releaseInputs(game: Active, port: Int) {
        game.input.remoteState(port, 0, 0f, 0f, 0f, 0f)
        game.onPointer(port, null)
        game.onMotion(port, null)
        previousPointer.remove(port)
        previousMotion.remove(port)
    }

    private fun cancelDeadman(session: String) {
        deadman.remove(session)?.let(handler::removeCallbacks)
    }

    /** Network loss must never leave a held button or a nonzero stick on the TV. */
    private fun resetDeadman(session: String, packet: CompanionGamepadState) {
        cancelDeadman(session)
        if (packet.buttons == 0 && packet.leftX == 0f && packet.leftY == 0f &&
            packet.rightX == 0f && packet.rightY == 0f &&
            packet.pointer?.let { it.contacts.any { point -> point.pressed } || it.buttons != 0 } != true &&
            packet.motion == null) return
        val game = active ?: return
        val slot = ports[session] ?: return
        val task = Runnable {
            deadman.remove(session)
            if (active === game && ports[session] == slot)
                releaseInputs(game, slot)
        }
        deadman[session] = task
        handler.postDelayed(task, 1_500L)
    }

    fun attach(input: RetroPadInput, systemId: String, coreId: String, title: String,
               pointerInput: Boolean, onMenu: () -> Unit,
               onPointer: (Int, CompanionPointerState?) -> Unit = { _, _ -> },
               onMotion: (Int, CompanionMotionState?) -> Unit = { _, _ -> }) {
        if (active?.input === input && active?.info?.systemId == systemId &&
            active?.info?.coreId == coreId && active?.info?.title == title.take(120)) return
        detachCurrent()
        active = Active(input, CompanionGamepadInfo(
            sessionId = UUID.randomUUID().toString(),
            systemId = systemId.take(40), coreId = coreId.take(80),
            title = title.take(120), pointerInput = pointerInput,
        ), onMenu, onPointer, onMotion)
    }

    fun setReady(input: RetroPadInput, ready: Boolean) {
        val game = active?.takeIf { it.input === input } ?: return
        if (game.ready == ready) return
        game.ready = ready
        if (!ready) {
            deadman.keys.toList().forEach(::cancelDeadman)
            ports.values.forEach { releaseInputs(game, it) }
        }
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
            releaseInputs(game, port)
            return false
        }
        game.input.remoteState(port, packet.buttons, packet.leftX, packet.leftY, packet.rightX, packet.rightY)
        // Relative mouse movement is an event, not an absolute position: identical consecutive
        // deltas are still distinct movement and must not disappear in deduplication.
        if (packet.pointer != previousPointer[port] || packet.pointer?.let {
            it.kind == "mouse" && (it.dx != 0f || it.dy != 0f)
        } == true) {
            game.onPointer(port, packet.pointer)
            if (packet.pointer == null) previousPointer.remove(port)
            else previousPointer[port] = packet.pointer
        }
        if (packet.motion != previousMotion[port]) {
            game.onMotion(port, packet.motion)
            if (packet.motion == null) previousMotion.remove(port)
            else previousMotion[port] = packet.motion
        }
        resetDeadman(session, packet)
        return true
    }

    fun menu(session: String): Boolean {
        val game = active ?: return false
        if (session !in ports) return false
        game.onMenu()
        return true
    }

    fun release(session: String) {
        cancelDeadman(session)
        val port = ports.remove(session) ?: return
        active?.let { releaseInputs(it, port); it.input.reserveRemotePort(port, false) }
    }

    fun detach(input: RetroPadInput) {
        if (active?.input === input) detachCurrent()
    }

    private fun detachCurrent() {
        deadman.keys.toList().forEach(::cancelDeadman)
        val game = active ?: return
        ports.values.forEach { releaseInputs(game, it); game.input.reserveRemotePort(it, false) }
        ports.clear()
        active = null
    }
}
