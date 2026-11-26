package com.ambient.tv.moonlight

import com.ambient.tv.companion.CompanionGamepadSink
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import kotlin.math.roundToInt
import com.ambient.tv.ui.gamepad.retro.RetroInputProfileContext
import com.ambient.tv.ui.gamepad.retro.RetroInputKind
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.companionControllerKey
import com.ambient.tv.ui.gamepad.retro.companionMappedButtons

/**
 * Authorized phone controller -> Moonlight GameStream. The existing Companion TLS lease,
 * session revision, heartbeat, deadman timer and port assignment are reused unchanged.
 */
internal class MoonlightCompanionSink(
    private val engine: MoonlightStreamEngine,
    private val owner: Any,
    private val localPorts: () -> Set<Int>,
    private val profileContext: RetroInputProfileContext?,
    private val onMenu: () -> Unit,
) : CompanionGamepadSink {
    private val reserved = mutableSetOf<Int>()
    private val descriptors = arrayOfNulls<String>(4)
    private val mappedPrevious = IntArray(4)
    private val suppressedButtons = IntArray(4)
    private val previousButtons = IntArray(4)
    private val suppressedAnalog = BooleanArray(4)
    private val suppressedTriggers = BooleanArray(4)
    private val profileCache = arrayOfNulls<com.ambient.tv.ui.gamepad.retro.RetroInputProfile>(4)
    private var cachedConfig = RetroInputSettings.config
    fun reservedPorts(): Set<Int> = reserved.toSet()
    fun republishReservations() { reserved.forEach { engine.setRemotePortActive(owner, it, true) } }
    override fun localUsedPorts(): Set<Int> = localPorts()
    override val allowSharedLocalPort: Boolean get() = false

    override fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String?) {
        if (port !in 0..3) return
        if (reserved) {
            if (descriptors[port] != descriptor) profileCache[port] = null
            this.reserved.add(port); descriptors[port] = descriptor
        } else {
            profileCache[port] = null
            descriptors[port] = null
            mappedPrevious[port] = 0
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
        // Reservation is published on claim or session republish, not every input.
        // RetroPad labels are SNES positions: B bottom/A right/Y left/X top.
        // Windows XInput's A/B/X/Y are bottom/right/left/top respectively.
        previousButtons[port] = buttons
        suppressedButtons[port] = suppressedButtons[port] and buttons
        val visible = buttons and suppressedButtons[port].inv()
        val config = RetroInputSettings.config
        val descriptor = descriptors[port]
        if (cachedConfig !== config) {
            cachedConfig = config
            profileCache.fill(null)
        }
        val profile = profileCache[port] ?: (profileContext?.let { config.profile(RetroInputKind.COMPANION, it, deviceDescriptor = descriptor) }
            ?: descriptor?.let { config.deviceProfile(RetroInputKind.COMPANION, it) } ?: config.profile(RetroInputKind.COMPANION))
            .also { profileCache[port] = it }
        val rising = visible and mappedPrevious[port].inv()
        mappedPrevious[port] = visible
        var mappedButtons = companionMappedButtons(visible, profile)
        if (RetroPadButton.entries.any { rising and it.mask != 0 && profile.opensMenu(companionControllerKey(it)) } ||
            rising != 0 && config.menuCombo.matches(mappedButtons)) {
            clearRemotePort(port, suppressHeld = true)
            onMenu()
            return
        }
        for (source in RetroPadButton.entries) {
            val key = companionControllerKey(source)
            if (profile.opensMenu(key) || profile.holdOpensMenu(key))
                profile.button(key)?.let { mappedButtons = mappedButtons and it.mask.inv() }
        }
        val mapped = moonlightRetroButtonFlags(mappedButtons)
        if (maxOf(kotlin.math.abs(leftX), kotlin.math.abs(leftY), kotlin.math.abs(rightX), kotlin.math.abs(rightY)) < .12f)
            suppressedAnalog[port] = false
        if ((leftTrigger ?: 0f) < .1f && (rightTrigger ?: 0f) < .1f &&
            visible and (RetroPadButton.L2.mask or RetroPadButton.R2.mask) == 0)
            suppressedTriggers[port] = false
        fun short(value: Float) = (value.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        fun trigger(button: RetroPadButton): Byte {
            if (suppressedTriggers[port]) return 0
            var pressure = if (mappedButtons and button.mask != 0) 1f else 0f
            for ((source, value) in listOf(RetroPadButton.L2 to leftTrigger, RetroPadButton.R2 to rightTrigger)) {
                if (profile.button(companionControllerKey(source)) == button && value != null) {
                    // Analog pressure takes precedence over the same source's digital report.
                    pressure = value.takeIf { it.isFinite() } ?: 0f
                }
            }
            return (pressure.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }
        engine.sendRemoteGamepad(owner, 
            port, mapped, trigger(RetroPadButton.L2),
            trigger(RetroPadButton.R2),
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

