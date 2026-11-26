package com.ambient.tv.companion

import com.ambient.tv.ui.gamepad.retro.RetroPadInput

/**
 * Game-controller input sink owned by the active TV game, independent of the emulator engine.
 * The authenticated Companion TLS protocol, port claims, pulse gate and disconnect deadman
 * stay exactly the same for Libretro and Moonlight. The receiver controls which game owns input.
 */
internal interface CompanionGamepadSink {
    val allowSharedLocalPort: Boolean get() = true
    fun localUsedPorts(): Set<Int>
    fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String? = null)
    fun clearRemotePort(port: Int, suppressHeld: Boolean = false)
    fun remoteInputDescriptions(port: Int, labels: Map<String, String>): Map<String, String>
    fun remoteState(
        port: Int, buttons: Int, leftX: Float, leftY: Float,
        rightX: Float, rightY: Float,
        leftTrigger: Float? = null, rightTrigger: Float? = null,
    )
}

/** Exact old Libretro behavior retained behind the common Gamepad sink contract. */
internal class LibretroCompanionGamepadSink(private val input: RetroPadInput) : CompanionGamepadSink {
    override fun localUsedPorts(): Set<Int> = input.localUsedPorts()
    override fun reserveRemotePort(port: Int, reserved: Boolean, descriptor: String?) =
        input.reserveRemotePort(port, reserved, descriptor)
    override fun clearRemotePort(port: Int, suppressHeld: Boolean) =
        input.clearRemotePort(port, suppressHeld)
    override fun remoteInputDescriptions(port: Int, labels: Map<String, String>): Map<String, String> =
        input.remoteInputDescriptions(port, labels)
    override fun remoteState(
        port: Int, buttons: Int, leftX: Float, leftY: Float,
        rightX: Float, rightY: Float, leftTrigger: Float?, rightTrigger: Float?,
    ) = input.remoteState(port, buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger)
}
