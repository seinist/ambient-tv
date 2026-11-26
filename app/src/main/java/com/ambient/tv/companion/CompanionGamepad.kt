package com.ambient.tv.companion

import android.os.Handler
import android.os.Looper
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.games.GameInputDescriptions
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A paused core is not necessarily a menu: loading, state IO and backgrounding stay inert. */
enum class CompanionGamepadInteraction(val key: String) {
    LOADING("loading"), PLAYING("playing"), MENU("menu"), DIALOG("dialog"),
    BUSY("busy"), BACKGROUND("background");

    val navigation: Boolean get() = this == MENU || this == DIALOG

    companion object {
        fun fromKey(key: String?): CompanionGamepadInteraction? = entries.firstOrNull { it.key == key }
    }
}

/** Every discrete menu/rewind command belongs to one game and one interaction context. */
@Serializable
data class CompanionGamepadCommand(
    val sessionId: String,
    val contextRevision: Long,
    val action: String,
    val pressed: Boolean = false,
) {
    fun validate() {
        require(sessionId.isNotBlank() && sessionId.length <= 64 && contextRevision >= 0L)
        require(action in COMPANION_GAMEPAD_ACTIONS)
        require(!pressed || action == "rewind")
    }
}

internal val COMPANION_GAMEPAD_NAVIGATION = setOf("up", "down", "left", "right", "ok", "back")
internal val COMPANION_GAMEPAD_CONFIRM = setOf("ok_down", "ok_up", "ok_cancel", "ok_renew")
private val COMPANION_GAMEPAD_ACTIONS = COMPANION_GAMEPAD_NAVIGATION + COMPANION_GAMEPAD_CONFIRM +
    setOf("menu", "resume", "rewind")

@Serializable
data class CompanionRumble(val low: Int, val high: Int, val pulse: Long)

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
    /** Null on older receivers. Unknown future modes never grant menu or gameplay control. */
    val interaction: String? = null,
    val contextRevision: Long = 0,
    /** Rewind is global: only the primary player's claim receives this capability. */
    val rewindAvailable: Boolean = false,
    val rewindSeconds: Float = 0f,
    /** A netplay handshake may leave the menu usable while continuation is unsafe. */
    val canResume: Boolean = true,
    val rumble: CompanionRumble? = null,
)

internal val CompanionGamepadInfo.interactionMode: CompanionGamepadInteraction?
    get() = CompanionGamepadInteraction.fromKey(interaction)

internal val CompanionGamepadInfo.acceptsGameplay: Boolean
    get() = ready && (interaction == null || interactionMode == CompanionGamepadInteraction.PLAYING)

internal val CompanionGamepadInfo.acceptsNavigation: Boolean
    get() = !ready && interactionMode?.navigation == true

internal val CompanionGamepadInfo.acceptsResume: Boolean
    get() = acceptsNavigation && canResume

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
    /** Optional context ticket. Legacy senders are still limited by the current ready gate. */
    val sessionId: String = "",
    val contextRevision: Long? = null,
) {
    fun validate() {
        require(buttons in 0..0xffff)
        require(listOf(leftX, leftY, rightX, rightY).all { it.isFinite() && it in -1f..1f })
        require(leftTrigger == null || leftTrigger.isFinite() && leftTrigger in 0f..1f)
        require(rightTrigger == null || rightTrigger.isFinite() && rightTrigger in 0f..1f)
        require(sessionId.length <= 64 && (contextRevision == null || contextRevision >= 0L))
        require((sessionId.isEmpty()) == (contextRevision == null))
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
    return previous.sessionId != next.sessionId || previous.contextRevision != next.contextRevision ||
        previous.buttons != next.buttons ||
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
        val owner: Any,
        val input: CompanionGamepadSink,
        var info: CompanionGamepadInfo,
        val onMenu: () -> Unit,
        val onPointer: (Int, CompanionPointerState?) -> Unit,
        val onMotion: (Int, CompanionMotionState?) -> Unit,
        val onRewind: (Boolean) -> Unit,
        var ready: Boolean = false,
        var rewindSession: String? = null,
        var interactionContext: Any? = null,
        var confirm: Confirm? = null,
        val rumble: MutableMap<Int, Pair<Int, Int>> = mutableMapOf(),
    )

    private class Confirm(val session: String, val revision: Long, val release: (Boolean) -> Unit) {
        var deadline = 0L
        var watchdog: Runnable? = null
    }

    private var active: Active? = null
    private val ports = LinkedHashMap<String, Int>()
    private val deviceFingerprints = LinkedHashMap<String, String>()
    private val mutablePlayerPorts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Stable phone identity -> claimed player port. Only membership/port changes publish a value. */
    val playerPorts = mutablePlayerPorts.asStateFlow()

    private fun publishPlayerPorts() {
        mutablePlayerPorts.value = deviceFingerprints.mapNotNull { (session, fingerprint) ->
            ports[session]?.let { fingerprint to it }
        }.toMap()
    }
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
    private var rewindDeadline = 0L
    private var rewindDeadman: Runnable? = null

    private fun releaseConfirm(game: Active, cancelled: Boolean = true) {
        val confirm = game.confirm ?: return
        game.confirm = null
        confirm.watchdog?.let(handler::removeCallbacks)
        confirm.release(cancelled)
    }

    private fun renewConfirm(game: Active, confirm: Confirm) {
        confirm.deadline = android.os.SystemClock.uptimeMillis() + 1_500L
        if (confirm.watchdog != null) return
        lateinit var task: Runnable
        task = Runnable {
            if (active !== game || game.confirm !== confirm || confirm.session !in ports) {
                if (game.confirm === confirm) releaseConfirm(game)
            } else {
                val remaining = confirm.deadline - android.os.SystemClock.uptimeMillis()
                if (remaining > 0L) handler.postDelayed(task, remaining) else releaseConfirm(game)
            }
        }
        confirm.watchdog = task
        handler.postDelayed(task, 1_500L)
    }

    private fun stopRewind(game: Active) {
        rewindDeadman?.let(handler::removeCallbacks)
        rewindDeadman = null
        rewindDeadline = 0L
        if (game.rewindSession != null) {
            game.rewindSession = null
            game.onRewind(false)
        }
    }

    private fun holdRewind(game: Active, session: String) {
        if (game.rewindSession != session) {
            stopRewind(game)
            game.rewindSession = session
            game.onRewind(true)
        }
        rewindDeadline = android.os.SystemClock.uptimeMillis() + 1_500L
        if (rewindDeadman != null) return
        lateinit var task: Runnable
        task = Runnable {
            if (active !== game || game.rewindSession != session || ports[session] != 0) {
                stopRewind(game)
            } else {
                val remaining = rewindDeadline - android.os.SystemClock.uptimeMillis()
                if (remaining > 0L) handler.postDelayed(task, remaining) else stopRewind(game)
            }
        }
        rewindDeadman = task
        handler.postDelayed(task, 1_500L)
    }

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

    private fun releaseInputs(game: Active, port: Int, suppressHeld: Boolean = false, releaseMenu: Boolean = true) {
        if (releaseMenu && game.confirm?.session?.let { ports[it] == port } == true) releaseConfirm(game)
        if (game.rewindSession?.let { ports[it] == port } == true) stopRewind(game)
        clearButtons(port)
        game.rumble.remove(port)
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
               onRewind: (Boolean) -> Unit = {},
               onMenu: () -> Unit) {
        if (active?.owner === input && active?.info?.systemId == systemId &&
            active?.info?.coreId == coreId && active?.info?.title == title.take(120)) return
        detachCurrent()
        active = Active(input, LibretroCompanionGamepadSink(input), CompanionGamepadInfo(
            sessionId = UUID.randomUUID().toString(),
            systemId = systemId.take(40), coreId = coreId.take(80),
            title = title.take(120), pointerInput = pointerInput,
            interaction = CompanionGamepadInteraction.LOADING.key,
        ), onMenu, onPointer, onMotion, onRewind)
    }

    /**
     * A Sunshine desktop/game can claim the same authenticated phone controller as Libretro.
     * Source and destination switching release all held inputs before changing ownership.
     */
    internal fun attachMoonlight(
        sink: CompanionGamepadSink, title: String,
        onPointer: (Int, CompanionPointerState?) -> Unit = { _, _ -> },
        onMotion: (Int, CompanionMotionState?) -> Unit = { _, _ -> },
        onMenu: () -> Unit,
    ) {
        if (active?.owner === sink && active?.info?.systemId == "moonlight") return
        detachCurrent()
        active = Active(sink, sink, CompanionGamepadInfo(
            sessionId = UUID.randomUUID().toString(), systemId = "moonlight",
            coreId = "gamestream", title = title.take(120), pointerInput = true,
            interaction = CompanionGamepadInteraction.LOADING.key,
        ), onMenu, onPointer, onMotion, onRewind = {})
    }

    internal fun setMoonlightInteraction(sink: CompanionGamepadSink, interaction: CompanionGamepadInteraction) {
        setInteractionForOwner(sink, interaction)
    }

    internal fun setMoonlightRumble(sink: CompanionGamepadSink, port: Int, low: Int, high: Int) {
        val game = active?.takeIf { it.owner === sink && it.ready } ?: return
        if (port !in ports.values) return
        if (low == 0 && high == 0) game.rumble.remove(port)
        else game.rumble[port] = low.coerceIn(0, 65535) to high.coerceIn(0, 65535)
    }

    internal fun detachMoonlight(sink: CompanionGamepadSink) {
        if (active?.owner === sink) detachCurrent()
    }

    fun setReady(input: RetroPadInput, ready: Boolean) {
        setInteraction(input, if (ready) CompanionGamepadInteraction.PLAYING else CompanionGamepadInteraction.BUSY)
    }

    fun setInteraction(input: RetroPadInput, interaction: CompanionGamepadInteraction, context: Any? = null,
                       canResume: Boolean = true) {
        setInteractionForOwner(input, interaction, context, canResume)
    }

    private fun setInteractionForOwner(owner: Any, interaction: CompanionGamepadInteraction,
                                       context: Any? = null, canResume: Boolean = true) {
        val game = active?.takeIf { it.owner === owner } ?: return
        if (game.info.interaction == interaction.key && game.interactionContext == context &&
            game.info.canResume == canResume) return
        releaseConfirm(game)
        game.interactionContext = context
        game.info = game.info.copy(interaction = interaction.key, contextRevision = game.info.contextRevision + 1,
            canResume = canResume)
        val ready = interaction == CompanionGamepadInteraction.PLAYING
        game.ready = ready
        if (!ready) {
            stopRewind(game)
            deadman.keys.toList().forEach(::cancelDeadman)
            ports.values.forEach { releaseInputs(game, it, suppressHeld = true) }
        }
    }

    /** Cheap status only; the player obtains the actual buffered duration outside this bridge. */
    fun setRewind(input: RetroPadInput, available: Boolean, seconds: Float) {
        val game = active?.takeIf { it.owner === input } ?: return
        val bounded = seconds.takeIf { it.isFinite() }?.coerceIn(0f, 60f) ?: 0f
        val enabled = available && bounded > 0f
        if (game.info.rewindAvailable != enabled || game.info.rewindSeconds != bounded) {
            game.info = game.info.copy(rewindAvailable = enabled, rewindSeconds = bounded)
        }
        if (!enabled) stopRewind(game)
    }

    fun setInputDescriptions(input: RetroPadInput, descriptions: Map<String, String>) {
        val game = active?.takeIf { it.owner === input } ?: return
        val bounded = GameInputDescriptions.normalized(descriptions)
        if (game.info.inputDescriptions != bounded) game.info = game.info.copy(inputDescriptions = bounded)
    }

    fun info(session: String? = null, includeDescriptions: Boolean = true): CompanionGamepadInfo? = active?.let { game ->
        val descriptions = when {
            !includeDescriptions -> null
            session == null -> game.info.inputDescriptions
            else -> game.input.remoteInputDescriptions(ports[session] ?: -1, game.info.inputDescriptions.orEmpty())
        }
        val rumble = if (game.ready && session != null) ports[session]?.let(game.rumble::get)?.let {
            CompanionRumble(it.first, it.second, android.os.SystemClock.elapsedRealtime() / 100L)
        } else null
        game.info.copy(ready = game.ready, inputDescriptions = descriptions, rumble = rumble,
            rewindAvailable = game.ready && game.info.rewindAvailable &&
                (session == null || ports[session] == 0))
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
            val collision = !game.input.allowSharedLocalPort && existing in game.input.localUsedPorts()
            if (!collision && configured in 0 until RetroPorts.COUNT && configured == existing) return existing
            if (!collision && configured == RetroPorts.AUTO && session !in forceAutoReassignment) return existing
            release(session) // Changed fixed/automatic assignment takes effect on this claim.
        }
        val occupiedPhones = ports.values.toSet()
        val port = if (configured in 0 until RetroPorts.COUNT) {
            configured.takeUnless { it in occupiedPhones ||
                (!game.input.allowSharedLocalPort && it in game.input.localUsedPorts()) } ?: return -1
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
        publishPlayerPorts()
        return port
    }

    fun input(session: String, packet: CompanionGamepadState): Boolean {
        packet.validate()
        val game = active ?: return false
        if (packet.contextRevision != null && (packet.sessionId != game.info.sessionId ||
                packet.contextRevision != game.info.contextRevision)) return false
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
            // A late neutral gameplay packet cannot cancel a new OK hold in the OSD.
            releaseInputs(game, port, suppressHeld = true, releaseMenu = false)
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
        if (!refreshClaim(session) || (!game.ready &&
                (game.info.interactionMode?.navigation != true || !game.info.canResume))) return false
        setInteractionForOwner(game.owner, if (game.ready) CompanionGamepadInteraction.MENU else CompanionGamepadInteraction.BUSY,
            context = game.interactionContext, canResume = game.info.canResume)
        game.onMenu()
        return true
    }

    /** Game-controller claims authorize only this game, never the app-wide remote lease. */
    fun command(session: String, command: CompanionGamepadCommand): Boolean = command(session, command,
        navigate = { menuInputReady() && CompanionRemoteInput.dispatch(it) },
        beginConfirm = { port -> if (menuInputReady()) CompanionRemoteInput.beginGameConfirm(-64 - port) else null })

    private fun menuInputReady(): Boolean {
        val mode = com.ambient.tv.ui.gamepad.GameInputRouting.mode
        // Native callbacks may precede Compose's route publication by one frame.
        return mode == com.ambient.tv.ui.gamepad.GameInputMode.MENU || mode == com.ambient.tv.ui.gamepad.GameInputMode.DIALOG
    }

    internal fun command(session: String, command: CompanionGamepadCommand,
                         navigate: (CompanionInput) -> Boolean): Boolean = command(session, command, { null }, navigate)

    internal fun command(session: String, command: CompanionGamepadCommand,
                         beginConfirm: (Int) -> ((Boolean) -> Unit)?,
                         navigate: (CompanionInput) -> Boolean): Boolean {
        command.validate()
        val game = active ?: return false
        if (command.sessionId != game.info.sessionId || !refreshClaim(session)) return false
        // A release may have been queued before a pause. It is always safe for its owner.
        if (command.action == "rewind" && !command.pressed) {
            if (game.rewindSession == session) stopRewind(game)
            return true
        }
        if (command.action == "ok_up" || command.action == "ok_cancel") {
            val confirm = game.confirm
            if (confirm?.session == session && confirm.revision == command.contextRevision) {
                releaseConfirm(game, cancelled = command.action == "ok_cancel" ||
                    game.info.contextRevision != confirm.revision || game.info.interactionMode?.navigation != true)
            }
            return true
        }
        if (command.contextRevision != game.info.contextRevision) return false
        return when (command.action) {
            in COMPANION_GAMEPAD_NAVIGATION -> if (game.info.interactionMode?.navigation == true && !game.ready) {
                releaseConfirm(game)
                navigate(CompanionInput("key", key = command.action))
            } else false
            "ok_down" -> if (game.info.interactionMode?.navigation == true && !game.ready && game.confirm == null) {
                val release = beginConfirm(ports.getValue(session)) ?: return false
                Confirm(session, command.contextRevision, release).also {
                    game.confirm = it
                    renewConfirm(game, it)
                }
                true
            } else false
            "ok_renew" -> game.confirm?.takeIf { it.session == session && it.revision == command.contextRevision }
                ?.let { renewConfirm(game, it); true } ?: false
            "menu" -> game.ready && menu(session)
            "resume" -> game.info.interactionMode?.navigation == true && menu(session)
            "rewind" -> if (game.ready && game.info.rewindAvailable && ports[session] == 0) {
                holdRewind(game, session)
                true
            } else false
            else -> false
        }
    }

    private fun refreshClaim(session: String): Boolean {
        val fingerprint = deviceFingerprints[session] ?: return false
        val configured = com.ambient.tv.ui.gamepad.retro.RetroInputSettings.config.ports[
            com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor(fingerprint)] ?: RetroPorts.AUTO
        if (configured != lastPortSetting[session]) {
            if (configured == RetroPorts.AUTO) forceAutoReassignment.add(session)
            return claim(session, fingerprint) >= 0
        }
        return session in ports
    }

    fun release(session: String) {
        cancelDeadman(session)
        deviceFingerprints.remove(session)
        forceAutoReassignment.remove(session)
        lastPortSetting.remove(session)
        // Clear while this TLS session still owns its slot. clearButtons() resolves ownership
        // from ports; removing the mapping first leaked pulse gates and delayed callbacks.
        val port = ports[session] ?: run { publishPlayerPorts(); return }
        active?.let { releaseInputs(it, port); it.input.reserveRemotePort(port, false) }
        ports.remove(session)
        publishPlayerPorts()
    }

    fun detach(input: RetroPadInput) {
        if (active?.owner === input) detachCurrent()
    }

    private fun detachCurrent() {
        deadman.keys.toList().forEach(::cancelDeadman)
        val game = active ?: return
        releaseConfirm(game)
        stopRewind(game)
        ports.values.forEach { releaseInputs(game, it); game.input.reserveRemotePort(it, false) }
        ports.clear()
        deviceFingerprints.clear()
        publishPlayerPorts()
        forceAutoReassignment.clear()
        lastPortSetting.clear()
        active = null
    }
}
