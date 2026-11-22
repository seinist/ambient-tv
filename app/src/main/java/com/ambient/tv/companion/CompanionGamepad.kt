package com.ambient.tv.companion

import android.os.Handler
import android.os.Looper
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.games.GameInputDescriptions
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
    /** Optional per-port core action labels; older peers ignore this field. */
    val inputDescriptions: Map<String, String>? = null,
)

/** Small realtime ACKs omit descriptions. They may only inherit metadata from this same game. */
internal fun companionGamepadInfoWithMetadata(previous: CompanionGamepadInfo?, incoming: CompanionGamepadInfo?): CompanionGamepadInfo? {
    if (incoming == null || incoming.inputDescriptions != null || previous == null || previous.sessionId != incoming.sessionId) return incoming
    return incoming.copy(inputDescriptions = previous.inputDescriptions)
}

/** Libretro base device IDs. Core-specific extensions retain their high-byte subtype. */
internal fun companionCoreDeviceType(kind: String?): Int = when (kind) {
    "mouse" -> 2 // RETRO_DEVICE_MOUSE
    "gun" -> 4 // RETRO_DEVICE_LIGHTGUN
    "touch" -> 6 // RETRO_DEVICE_POINTER
    else -> 1 // RETRO_DEVICE_JOYPAD
}

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
    /** Null means an older peer: derive full pressure from its L2/R2 button bits. */
    val leftTrigger: Float? = null,
    val rightTrigger: Float? = null,
) {
    fun validate() {
        require(buttons in 0..0xffff)
        require(listOf(leftX, leftY, rightX, rightY).all { it.isFinite() && it in -1f..1f })
        require(leftTrigger == null || leftTrigger.isFinite() && leftTrigger in 0f..1f)
        require(rightTrigger == null || rightTrigger.isFinite() && rightTrigger in 0f..1f)
        pointer?.validate()
        motion?.validate()
    }
}

/**
 * Edges (press/release, contact begin/end, gun reload and device/motion mode changes) must
 * remain ordered when the network queue is full. Continuous axes and drag coordinates
 * are safely superseded by a newer snapshot.
 */
internal fun companionGamepadCriticalEdge(
    previous: CompanionGamepadState?, next: CompanionGamepadState,
): Boolean {
    if (previous == null) return next.buttons != 0 || (next.leftTrigger ?: 0f) > 0f ||
        (next.rightTrigger ?: 0f) > 0f || next.pointer?.contacts?.any { it.pressed } == true ||
        (next.pointer?.buttons ?: 0) != 0 || (next.motion?.mask ?: 0) != 0
    val a = previous.pointer
    val b = next.pointer
    return previous.buttons != next.buttons ||
        ((previous.leftTrigger ?: 0f) > 0f) != ((next.leftTrigger ?: 0f) > 0f) ||
        ((previous.rightTrigger ?: 0f) > 0f) != ((next.rightTrigger ?: 0f) > 0f) || a?.kind != b?.kind ||
        a?.buttons != b?.buttons || a?.offscreen != b?.offscreen ||
        a?.contacts?.filter { it.pressed }?.map { it.index } !=
            b?.contacts?.filter { it.pressed }?.map { it.index } ||
        previous.motion?.mask != next.motion?.mask
}

/**
 * Main-thread bridge between authenticated Companion TLS sessions and the active Libretro core.
 * Independent of TV remote-control leases: up to four paired phones can play together.
 * All entry points run on the main dispatcher (including release on socket teardown).
 */
object CompanionGamepadSession {
    private class Active(
        val input: RetroPadInput,
        var info: CompanionGamepadInfo,
        val onMenu: () -> Unit,
        val onPointer: (Int, CompanionPointerState?) -> Unit,
        val onMotion: (Int, CompanionMotionState?) -> Unit,
        var ready: Boolean = false,
    )

    private var active: Active? = null
    private val ports = LinkedHashMap<String, Int>()
    private val deviceFingerprints = LinkedHashMap<String, String>()
    private val forceAutoReassignment = mutableSetOf<String>()
    private val lastPortSetting = mutableMapOf<String, Int>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val deadman = LinkedHashMap<String, Runnable>()
    private val deadlines = LinkedHashMap<String, Long>()
    private val previousPointer = mutableMapOf<Int, CompanionPointerState>()
    private val previousMotion = mutableMapOf<Int, CompanionMotionState>()
    private val buttonGates = mutableMapOf<String, CompanionButtonEdgeGate>()
    private val triggerEdges = mutableMapOf<String, CompanionTriggerEdges>()
    private val lastState = mutableMapOf<String, CompanionGamepadState>()
    private val delayedRelease = mutableMapOf<String, Runnable>()

    private fun clearButtons(port: Int) {
        ports.filterValues { it == port }.keys.forEach { session ->
            buttonGates.remove(session)?.clear()
            triggerEdges.remove(session)
            lastState.remove(session)
            delayedRelease.remove(session)?.let(handler::removeCallbacks)
        }
    }

    private fun scheduleButtonRelease(session: String, game: Active, port: Int) {
        if (session in delayedRelease) return
        val gate = buttonGates[session] ?: return
        val delay = gate.nextReleaseDelay(android.os.SystemClock.uptimeMillis()) ?: return
        lateinit var task: Runnable
        task = Runnable {
            delayedRelease.remove(session)
            if (active === game && ports[session] == port) {
                val current = lastState[session]
                if (current != null) {
                    val mask = gate.settle(android.os.SystemClock.uptimeMillis())
                    val triggers = triggerEdges.getOrPut(session) { CompanionTriggerEdges() }
                    game.input.remoteState(port, mask, current.leftX, current.leftY,
                        current.rightX, current.rightY,
                        triggers.pressure(0, current.buttons, mask, current.leftTrigger),
                        triggers.pressure(1, current.buttons, mask, current.rightTrigger))
                    scheduleButtonRelease(session, game, port)
                }
            }
        }
        delayedRelease[session] = task
        handler.postDelayed(task, delay)
    }

    private fun releaseInputs(game: Active, port: Int, suppressHeld: Boolean = false) {
        clearButtons(port)
        game.input.clearRemotePort(port, suppressHeld = suppressHeld)
        game.onPointer(port, null)
        game.onMotion(port, null)
        previousPointer.remove(port)
        previousMotion.remove(port)
    }

    private fun cancelDeadman(session: String) {
        deadlines.remove(session)
        deadman.remove(session)?.let(handler::removeCallbacks)
    }

    /** A watchdog callback is scheduled once per active controller, not 30 times per second.
     * Incoming packets merely extend its deadline. This keeps the main thread free for frames.
     */
    private fun resetDeadman(session: String, packet: CompanionGamepadState) {
        if (packet.buttons == 0 && packet.leftX == 0f && packet.leftY == 0f &&
            packet.rightX == 0f && packet.rightY == 0f &&
            (packet.leftTrigger ?: 0f) == 0f && (packet.rightTrigger ?: 0f) == 0f &&
            packet.pointer?.let { it.contacts.any { point -> point.pressed } || it.buttons != 0 } != true &&
            packet.motion == null) {
            cancelDeadman(session)
            return
        }
        val game = active ?: return
        val slot = ports[session] ?: return
        deadlines[session] = android.os.SystemClock.uptimeMillis() + 1_500L
        if (deadman.containsKey(session)) return
        lateinit var task: Runnable
        task = Runnable {
            if (active !== game || ports[session] != slot) {
                cancelDeadman(session)
            } else {
                val remaining = (deadlines[session] ?: 0L) - android.os.SystemClock.uptimeMillis()
                if (remaining > 0L) handler.postDelayed(task, remaining)
                else {
                    deadlines.remove(session)
                    deadman.remove(session)
                    releaseInputs(game, slot)
                }
            }
        }
        deadman[session] = task
        handler.postDelayed(task, 1_500L)
    }

    fun attach(input: RetroPadInput, systemId: String, coreId: String, title: String,
               pointerInput: Boolean,
               onPointer: (Int, CompanionPointerState?) -> Unit = { _, _ -> },
               onMotion: (Int, CompanionMotionState?) -> Unit = { _, _ -> },
               onMenu: () -> Unit) {
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
            ports.values.forEach { releaseInputs(game, it, suppressHeld = true) }
        }
    }

    fun setInputDescriptions(input: RetroPadInput, descriptions: Map<String, String>) {
        val game = active?.takeIf { it.input === input } ?: return
        val bounded = GameInputDescriptions.normalized(descriptions)
        if (game.info.inputDescriptions != bounded) game.info = game.info.copy(inputDescriptions = bounded)
    }

    fun info(session: String? = null, includeDescriptions: Boolean = true): CompanionGamepadInfo? = active?.let { game ->
        val descriptions = when {
            !includeDescriptions -> null
            session == null -> game.info.inputDescriptions
            else -> game.input.remoteInputDescriptions(ports[session] ?: -1, game.info.inputDescriptions.orEmpty())
        }
        game.info.copy(ready = game.ready, inputDescriptions = descriptions)
    }
    fun port(session: String): Int = ports[session] ?: -1

    /**
     * Phone identity is the pinned Companion fingerprint, not a transient TLS nonce or the
     * display name. The existing game-input config persists the same descriptor as hardware.
     * Explicit fixed ports may intentionally share a local physical player's port; two phones
     * never silently share one port. An OFF setting disables this handset completely.
     */
    fun claim(session: String, fingerprint: String = session): Int {
        val game = active ?: return -1
        val descriptor = com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor(fingerprint)
        val configured = com.ambient.tv.ui.gamepad.retro.RetroInputSettings.config.ports[descriptor]
            ?: RetroPorts.AUTO
        if (configured == RetroPorts.OFF) {
            release(session)
            return -1
        }
        ports[session]?.let { existing ->
            if (configured in 0 until RetroPorts.COUNT && configured == existing) return existing
            if (configured == RetroPorts.AUTO && session !in forceAutoReassignment) return existing
            release(session) // Changed fixed/automatic assignment takes effect on this claim.
        }
        val occupiedPhones = ports.values.toSet()
        val port = if (configured in 0 until RetroPorts.COUNT) {
            configured.takeUnless { it in occupiedPhones } ?: return -1
        } else {
            val reserved = com.ambient.tv.ui.gamepad.retro.RetroInputSettings.config.ports.values
                .filter { it in 0 until RetroPorts.COUNT }.toSet()
            val occupied = occupiedPhones + reserved + game.input.localUsedPorts()
            (0 until RetroPorts.COUNT).firstOrNull { it !in occupied } ?: return -1
        }
        game.input.reserveRemotePort(port, true, descriptor)
        ports[session] = port
        deviceFingerprints[session] = fingerprint
        forceAutoReassignment.remove(session)
        lastPortSetting[session] = configured
        return port
    }

    fun input(session: String, packet: CompanionGamepadState): Boolean {
        packet.validate()
        val game = active ?: return false
        // A port setting changed during a live game takes effect on the next input; OFF
        // revokes control immediately, while a fixed-port change reclaims it safely.
        deviceFingerprints[session]?.let { fingerprint ->
            val target = com.ambient.tv.ui.gamepad.retro.RetroInputSettings.config.ports[
                com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor(fingerprint)]
            val configured = target ?: RetroPorts.AUTO
            if (configured != lastPortSetting[session]) {
                if (configured == RetroPorts.AUTO) forceAutoReassignment.add(session)
                if (claim(session, fingerprint) < 0) return false
            }
        }
        val port = ports[session] ?: return false
        if (!game.ready) {
            releaseInputs(game, port, suppressHeld = true)
            return false
        }
        val gate = buttonGates.getOrPut(session) { CompanionButtonEdgeGate() }
        lastState[session] = packet
        val visibleButtons = runCatching {
            gate.update(packet.buttons, android.os.SystemClock.uptimeMillis())
        }.getOrElse {
            // If a core fails to consume even a bounded run of tap edges, hard release the
            // entire virtual controller instead of leaving a button latched indefinitely.
            releaseInputs(game, port)
            return false
        }
        val triggers = triggerEdges.getOrPut(session) { CompanionTriggerEdges() }
        game.input.remoteState(port, visibleButtons, packet.leftX, packet.leftY, packet.rightX, packet.rightY,
            triggers.pressure(0, packet.buttons, visibleButtons, packet.leftTrigger),
            triggers.pressure(1, packet.buttons, visibleButtons, packet.rightTrigger))
        scheduleButtonRelease(session, game, port)
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
        deviceFingerprints.remove(session)
        forceAutoReassignment.remove(session)
        lastPortSetting.remove(session)
        // Clear while this TLS session still owns its slot. clearButtons() resolves ownership
        // from ports; removing the mapping first leaked pulse gates and delayed callbacks.
        val port = ports[session] ?: return
        active?.let { releaseInputs(it, port); it.input.reserveRemotePort(port, false) }
        ports.remove(session)
    }

    fun detach(input: RetroPadInput) {
        if (active?.input === input) detachCurrent()
    }

    private fun detachCurrent() {
        deadman.keys.toList().forEach(::cancelDeadman)
        val game = active ?: return
        ports.values.forEach { releaseInputs(game, it); game.input.reserveRemotePort(it, false) }
        ports.clear()
        deviceFingerprints.clear()
        forceAutoReassignment.clear()
        lastPortSetting.clear()
        active = null
    }
}
