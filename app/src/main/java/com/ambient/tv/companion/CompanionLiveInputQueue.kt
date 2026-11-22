package com.ambient.tv.companion

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A bounded-latency input mailbox shared by the phone gamepad and the ordinary TV touchpad.
 *
 * Press/release/menu/click events stay FIFO and are never silently discarded because the normal
 * 16-slot command channel is full. Analog/pointer positions supersede obsolete samples rather
 * than accumulating 30 Hz worth of stale frames during a slow network round trip.
 *
 * A bounded edge queue holds genuine discrete user interactions, not motion samples.
 * If that FIFO is exhausted, the TLS owner closes its session instead of losing a release.
 * It belongs to one authenticated Companion connection and is replaced on reconnect.
 */
internal class CompanionLiveInputQueue(
    private val onOverflow: () -> Unit = {},
    private val edgeCapacity: Int = 64,
) {
    private val edges = Channel<CompanionFrame>(edgeCapacity)
    private var overflowed = false

    /** A full FIFO is a broken transport, not an excuse to discard UP/release and stick keys. */
    private fun sendEdge(frame: CompanionFrame): Boolean {
        if (overflowed) return false
        if (edges.trySend(frame).isSuccess) return true
        overflowed = true
        onOverflow() // The owner closes this TLS connection so the TV clears all held input.
        return false
    }
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var gamepadMotion: CompanionFrame? = null
    private var remoteMotion: CompanionFrame? = null

    fun offerGamepad(frame: CompanionFrame, critical: Boolean): Boolean {
        require(frame.type in setOf("gamepad_claim", "gamepad_input", "gamepad_menu", "gamepad_command", "gamepad_release"))
        if (critical || frame.type != "gamepad_input") {
            synchronized(this) {
                // Relative mouse deltas are movements, not replaceable absolute positions:
                // send the outstanding movement BEFORE the next click/press/mode change.
                gamepadMotion?.takeIf { it.gamepadState?.pointer?.kind == "mouse" &&
                    (it.gamepadState.pointer.dx != 0f || it.gamepadState.pointer.dy != 0f)
                }?.let { if (!sendEdge(it)) return false }
                gamepadMotion = null
                return sendEdge(frame)
            }
        }
        synchronized(this) {
            val previous = gamepadMotion
            if (previous != null) {
                // Relative mouse movements are deltas: merge rather than replace them.
                val merged = coalesceCompanionInput(previous, frame)
                if (merged != null) gamepadMotion = merged
                else {
                    // An overflow or changed input mode must not discard an earlier mouse delta.
                    if (previous.gamepadState?.pointer?.kind == "mouse") {
                        if (!sendEdge(previous)) return false
                    }
                    gamepadMotion = frame
                }
            } else gamepadMotion = frame
        }
        signal.trySend(Unit)
        return true
    }

    fun offerRemote(frame: CompanionFrame): Boolean {
        require(frame.type == "input")
        val input = requireNotNull(frame.input)
        synchronized(this) {
            if (input.action in setOf("move", "scroll")) {
                val old = remoteMotion
                if (old != null) {
                    val merged = coalesceCompanionInput(old, frame)
                    if (merged != null) remoteMotion = merged
                    else {
                        // Keep both summed segments when direction or mode changes or the
                        // normalized sum would overflow; never lose distance during a drag.
                        if (!sendEdge(old)) return false
                        remoteMotion = frame
                    }
                } else remoteMotion = frame
                signal.trySend(Unit)
                return true
            }
            // A click acts at the most recently touched position, never one gesture behind.
            remoteMotion?.let { if (!sendEdge(it)) return false }
            remoteMotion = null
            return sendEdge(frame)
        }
    }

    @Synchronized private fun takeContinuous(): CompanionFrame? {
        gamepadMotion?.let { gamepadMotion = null; return it }
        remoteMotion?.let { remoteMotion = null; return it }
        return null
    }

    fun poll(normal: Channel<CompanionFrame>): CompanionFrame? =
        edges.tryReceive().getOrNull() ?: normal.tryReceive().getOrNull() ?: takeContinuous()

    /**
     * The normal command stream is never delayed if gamepad control is idle. The wake-up channel
     * is conflated: one pending notification is enough however fast the phone sensor samples.
     */
    suspend fun receive(normal: Channel<CompanionFrame>, timeoutMs: Long): CompanionFrame? {
        poll(normal)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            var next: CompanionFrame? = null
            while (next == null) {
                next = select {
                    edges.onReceive { it }
                    normal.onReceive { it }
                    signal.onReceive { takeContinuous() }
                }
            }
            next
        }
    }

    /** While a TV ACK is pending, allow newer motion to be sent without waiting for RTT.
     * The second item is true for an already-arrived reply, false for a new input command.
     * The caller bounds the number of outstanding requests to protect the TCP write buffer.
     */
    suspend fun receiveOrReply(normal: Channel<CompanionFrame>,
                               replies: ReceiveChannel<CompanionFrame>): Pair<CompanionFrame, Boolean> {
        poll(normal)?.let { return it to false }
        while (true) {
            val result = select<Pair<CompanionFrame?, Boolean>> {
                replies.onReceive { it to true }
                edges.onReceive { it to false }
                normal.onReceive { it to false }
                signal.onReceive { takeContinuous() to false }
            }
            result.first?.let { return it to result.second }
        }
    }

    fun close() {
        synchronized(this) {
            overflowed = true
            gamepadMotion = null
            remoteMotion = null
        }
        edges.close()
        signal.close()
    }
}
