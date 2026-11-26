package com.ambient.tv.moonlight

import com.ambient.tv.companion.CompanionGamepadSink
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import kotlin.math.roundToInt

/**
 * Authorized phone controller -> Moonlight GameStream. The existing Companion TLS lease,
 * session revision, heartbeat, deadman timer and port assignment are reused unchanged.
 */
internal class MoonlightCompanionSink(
    private val engine: MoonlightStreamEngine,
    private val owner: Any,
    private val localPorts: () -> Set<Int>,
) : CompanionGamepadSink {
    private val reserved = mutableSetOf<Int>()
    fun reservedPorts(): Set<Int> = reserved.toSet()
    override fun localUsedPorts(): Set<Int> = localPorts()

    override fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String?) {
        if (port !in 0..3) return
        if (reserved) this.reserved.add(port) else this.reserved.remove(port)
        engine.setRemotePortActive(owner, port, reserved)
    }

    override fun clearRemotePort(port: Int, suppressHeld: Boolean) {
        if (port !in reserved) return
        engine.sendRemoteGamepad(owner, port, 0, 0, 0, 0, 0, 0, 0)
    }

    override fun remoteInputDescriptions(port: Int, labels: Map<String, String>): Map<String, String> =
        RetroPadButton.entries.associate { button ->
            val title = when (button) {
                RetroPadButton.B -> "A"
                RetroPadButton.A -> "B"
                RetroPadButton.Y -> "X"
                RetroPadButton.X -> "Y"
                RetroPadButton.L -> "LB"
                RetroPadButton.R -> "RB"
                RetroPadButton.L2 -> "LT"
                RetroPadButton.R2 -> "RT"
                RetroPadButton.START -> "Start"
                RetroPadButton.SELECT -> "View"
                else -> button.key.uppercase()
            }
            val key = "$port:" + button.id
            key to (labels[key] ?: title)
        }

    override fun remoteState(
        port: Int, buttons: Int, leftX: Float, leftY: Float,
        rightX: Float, rightY: Float, leftTrigger: Float?, rightTrigger: Float?,
    ) {
        if (port !in reserved) return
        engine.setRemotePortActive(owner, port, true)
        // RetroPad labels are SNES positions: B bottom/A right/Y left/X top.
        // Windows XInput's A/B/X/Y are bottom/right/left/top respectively.
        val mapped = moonlightRetroButtonFlags(buttons)
        fun short(value: Float) = (value.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        fun trigger(value: Float?, button: RetroPadButton): Byte =
            ((value ?: if ((buttons and button.mask) != 0) 1f else 0f)
                .coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        engine.sendRemoteGamepad(owner, 
            port, mapped, trigger(leftTrigger, RetroPadButton.L2),
            trigger(rightTrigger, RetroPadButton.R2),
            short(leftX), short(leftY), short(rightX), short(rightY),
        )
    }

    fun releaseEverything() {
        reserved.toList().forEach {
            clearRemotePort(it, suppressHeld = false)
            reserveRemotePort(it, reserved = false)
        }
    }
}

/** Shared, pure conversion used by unit tests and Companion deadman release. */
internal fun moonlightRetroButtonFlags(bits: Int): Int {
    fun held(button: RetroPadButton) = bits and button.mask != 0
    var out = 0
    if (held(RetroPadButton.B)) out = out or 0x1000 // Xbox A
    if (held(RetroPadButton.A)) out = out or 0x2000 // Xbox B
    if (held(RetroPadButton.Y)) out = out or 0x4000 // Xbox X
    if (held(RetroPadButton.X)) out = out or 0x8000 // Xbox Y
    if (held(RetroPadButton.UP)) out = out or 0x0001
    if (held(RetroPadButton.DOWN)) out = out or 0x0002
    if (held(RetroPadButton.LEFT)) out = out or 0x0004
    if (held(RetroPadButton.RIGHT)) out = out or 0x0008
    if (held(RetroPadButton.L)) out = out or 0x0100
    if (held(RetroPadButton.R)) out = out or 0x0200
    if (held(RetroPadButton.START)) out = out or 0x0010
    if (held(RetroPadButton.SELECT)) out = out or 0x0020
    if (held(RetroPadButton.L3)) out = out or 0x0040
    if (held(RetroPadButton.R3)) out = out or 0x0080
    return out
}
