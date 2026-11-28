package com.ambient.tv.moonlight

import com.ambient.tv.companion.CompanionGamepadInteraction
import com.ambient.tv.companion.CompanionGamepadSession
import com.ambient.tv.companion.CompanionMotionState
import com.ambient.tv.companion.CompanionPointerContact
import com.ambient.tv.companion.CompanionPointerState
import com.ambient.tv.data.local.AppPreferences
import com.limelight.nvstream.jni.MoonBridge
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Fullscreen Activity owns the existing authenticated Companion phone-controller lease.
 * Reuses TLS, port claims, deadman timers and context revisions from Libretro. Gameplay,
 * gyro, touch and menu never create an independent unauthenticated network receiver.
 */
class MoonlightCompanionLink @Inject constructor(
    private val engine: MoonlightStreamEngine,
    private val preferences: AppPreferences,
) {
    private var owner: Any? = null
    private var sink: MoonlightCompanionSink? = null
    private var pointerOwner: Int? = null
    private var mouseDown = false
    private var touchUnsupported = false
    private val touches = LinkedHashMap<Int, CompanionPointerContact>()
    private val motionMasks = IntArray(4) { -1 }
    private var motionGeneration: Long? = null

    fun attach(owner: Any, title: String, usedPorts: () -> Set<Int>,
        profileContext: com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext? = null, onMenu: () -> Unit): Unit = synchronized(CompanionGamepadSession) {
        detach()
        this.owner = owner
        val current = MoonlightCompanionSink(engine, owner, usedPorts, profileContext, onMenu)
        sink = current
        CompanionGamepadSession.attachMoonlight(
            current, title,
            onMenu = onMenu,
            onPointer = ::pointer,
            onMotion = ::motion,
        )
    }

    fun interaction(playing: Boolean, menu: Boolean): Unit = synchronized(CompanionGamepadSession) {
        val current = sink ?: return
        if (playing && !menu) current.republishReservations()
        CompanionGamepadSession.setMoonlightInteraction(current, when {
            menu -> CompanionGamepadInteraction.MENU
            playing -> CompanionGamepadInteraction.PLAYING
            else -> CompanionGamepadInteraction.LOADING
        })
    }

    fun reservedPorts(): Set<Int> = synchronized(CompanionGamepadSession) { sink?.reservedPorts().orEmpty() }
    fun republishReservations() { synchronized(CompanionGamepadSession) { sink?.republishReservations() } }

    fun rumble(event: MoonlightRumble): Unit = synchronized(CompanionGamepadSession) {
        val current = sink ?: return
        if (event.port in current.reservedPorts())
            CompanionGamepadSession.setMoonlightRumble(current, event.port, event.low, event.high)
    }

    fun detach(): Unit = synchronized(CompanionGamepadSession) {
        val previous = sink ?: return
        sink = null
        CompanionGamepadSession.detachMoonlight(previous)
        previous.releaseEverything()
        clearPointer()
        owner = null
        motionMasks.fill(-1)
        motionGeneration = null
    }

    private fun clearPointer() {
        val source = (pointerOwner ?: 0) + 1
        touches.values.toList().forEach { contact ->
            engine.sendTouchEvent(owner ?: return, MoonBridge.LI_TOUCH_EVENT_CANCEL,
                contact.index, contact.x, contact.y, 0f, source = source)
        }
        touches.clear()
        if (mouseDown) engine.sendMouseButton(owner ?: return, false, source = (pointerOwner ?: 0) + 2)
        mouseDown = false
        pointerOwner = null
        touchUnsupported = false
    }

    private fun mousePressed(down: Boolean) {
        if (down == mouseDown) return
        mouseDown = down
        engine.sendMouseButton(owner ?: return, down, source = (pointerOwner ?: 0) + 2)
    }

    private fun pointer(port: Int, next: CompanionPointerState?) {
        if (next == null) {
            if (pointerOwner == port) clearPointer()
            return
        }
        if (pointerOwner != null && pointerOwner != port) return
        pointerOwner = port
        val (width, height) = engine.activeSize(owner ?: return) ?: return
        when (next.kind) {
            "mouse" -> {
                if (touches.isNotEmpty()) clearTouchOnly()
                engine.sendMouseMove(owner ?: return, (next.dx * width).roundToInt(),
                    (next.dy * height).roundToInt())
                mousePressed((next.buttons and 1) != 0)
            }
            "gun" -> {
                if (touches.isNotEmpty()) clearTouchOnly()
                next.contacts.firstOrNull()?.let { contact ->
                    engine.sendMousePosition(owner ?: return, (contact.x * width).roundToInt(),
                        (contact.y * height).roundToInt(), width, height)
                }
                mousePressed((next.buttons and 1) != 0 ||
                    next.contacts.any { it.pressed })
            }
            "touch" -> {
                val current = next.contacts.filter { it.pressed }
                    .associateBy { it.index }
                val obsolete = touches.keys - current.keys
                for (index in obsolete) {
                    touches.remove(index)?.let { old ->
                        engine.sendTouchEvent(owner ?: return, MoonBridge.LI_TOUCH_EVENT_UP,
                            index, old.x, old.y, 0f, source = port + 1)
                    }
                }
                for ((index, contact) in current) {
                    val before = touches[index]
                    val kind = if (before == null) MoonBridge.LI_TOUCH_EVENT_DOWN
                        else MoonBridge.LI_TOUCH_EVENT_MOVE
                    if (before == null || before.x != contact.x || before.y != contact.y) {
                        val result = if (touchUnsupported) MoonBridge.LI_ERR_UNSUPPORTED
                            else engine.sendTouchEvent(owner ?: return, kind, index, contact.x, contact.y, 1f, source = port + 1)
                        if (result == MoonBridge.LI_ERR_UNSUPPORTED) touchUnsupported = true
                    }
                    touches[index] = contact
                }
                if (touchUnsupported) {
                    // Older Sunshine hosts do not support touch; a single-finger mouse still works.
                    current.values.firstOrNull()?.let { contact ->
                        engine.sendMousePosition(owner ?: return, (contact.x * width).roundToInt(),
                            (contact.y * height).roundToInt(), width, height)
                    }
                    mousePressed(current.isNotEmpty() || (next.buttons and 1) != 0)
                } else mousePressed(false)
            }
        }
    }

    private fun clearTouchOnly() {
        touches.values.toList().forEach { contact ->
            engine.sendTouchEvent(owner ?: return, MoonBridge.LI_TOUCH_EVENT_UP,
                contact.index, contact.x, contact.y, 0f, source = (pointerOwner ?: 0) + 1)
        }
        touches.clear()
    }

    private fun motion(port: Int, state: CompanionMotionState?) {
        if (port !in 0..3) return
        if (state == null) {
            if (motionMasks[port] and 2 != 0 && motionMasks[port] >= 0)
                owner?.let { engine.clearRemoteMotion(it, port) }
            motionMasks[port] = -1
            return
        }
        val data = state
        val currentOwner = owner ?: return
        val generation = engine.generation(currentOwner)
        if (motionGeneration != generation) {
            motionMasks.fill(-1)
            motionGeneration = generation
        }
        if (motionMasks[port] != data.mask) {
            motionMasks[port] = data.mask
            engine.setRemoteMotionCapabilities(currentOwner, port, data.mask)
        }
        if (data.mask and 1 != 0)
            engine.sendMotion(currentOwner, port, MoonBridge.LI_MOTION_TYPE_ACCEL,
                moonlightAccelerationFromG(data.ax), moonlightAccelerationFromG(data.ay), moonlightAccelerationFromG(data.az))
        if (data.mask and 2 != 0)
            engine.sendMotion(currentOwner, port, MoonBridge.LI_MOTION_TYPE_GYRO,
                moonlightGyroDegrees(data.gx), moonlightGyroDegrees(data.gy), moonlightGyroDegrees(data.gz))
    }
}
