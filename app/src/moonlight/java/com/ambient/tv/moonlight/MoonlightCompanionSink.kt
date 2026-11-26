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
    private val suppressedButtons = IntArray(4)
    private val previousButtons = IntArray(4)
    private val suppressedAnalog = BooleanArray(4)
    private val suppressedTriggers = BooleanArray(4)
    fun reservedPorts(): Set<Int> = reserved.toSet()
    fun republishReservations() { reserved.forEach { engine.setRemotePortActive(owner, it, true) } }
    override fun localUsedPorts(): Set<Int> = localPorts()
    override val allowSharedLocalPort: Boolean get() = false

    override fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String?) {
        if (port !in 0..3) return
        if (reserved) this.reserved.add(port) else {
            this.reserved.remove(port)
            suppressedButtons[port] = 0
            previousButtons[port] = 0
            suppressedAnalog[port] = false
            suppressedTriggers[port] = false
        }
        engine.setRemotePortActive(owner, port, reserved)
    }

    override fun clearRemotePort(port: Int, suppressHeld: Boolean) {
        if (port !in reserved) return
        if (suppressHeld) {
            suppressedButtons[port] = suppressedButtons[port] or previousButtons[port]
            suppressedAnalog[port] = true
            suppressedTriggers[port] = true
        } else {
            suppressedButtons[port] = 0
            suppressedAnalog[port] = false
            suppressedTriggers[port] = false
        }
        previousButtons[port] = 0
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
        previousButtons[port] = buttons
        suppressedButtons[port] = suppressedButtons[port] and buttons
        val visible = buttons and suppressedButtons[port].inv()
        val mapped = moonlightRetroButtonFlags(visible)
        if (maxOf(kotlin.math.abs(leftX), kotlin.math.abs(leftY), kotlin.math.abs(rightX), kotlin.math.abs(rightY)) < .12f)
            suppressedAnalog[port] = false
        if ((leftTrigger ?: 0f) < .1f && (rightTrigger ?: 0f) < .1f &&
            visible and (RetroPadButton.L2.mask or RetroPadButton.R2.mask) == 0)
            suppressedTriggers[port] = false
        fun short(value: Float) = (value.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        fun trigger(value: Float?, button: RetroPadButton): Byte =
            ((if (suppressedTriggers[port]) 0f else value ?: if ((visible and button.mask) != 0) 1f else 0f)
                .coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        engine.sendRemoteGamepad(owner, 
            port, mapped, trigger(leftTrigger, RetroPadButton.L2),
            trigger(rightTrigger, RetroPadButton.R2),
            short(if (suppressedAnalog[port]) 0f else leftX),
            short(if (suppressedAnalog[port]) 0f else -leftY),
            short(if (suppressedAnalog[port]) 0f else rightX),
            short(if (suppressedAnalog[port]) 0f else -rightY),
        )
    }

    fun releaseEverything() {
        reserved.toList().forEach {
            clearRemotePort(it, suppressHeld = false)
            reserveRemotePort(it, reserved = false)
        }
    }
}

