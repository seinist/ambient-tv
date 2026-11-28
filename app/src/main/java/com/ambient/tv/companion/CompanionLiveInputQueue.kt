package com.ambient.tv.companion

import java.util.ArrayDeque
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

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
    init { require(edgeCapacity > 0) }
    // One monitor protects both the discrete FIFO and the replaceable samples.
    // The signal is a wake-up only; select must never dequeue from a second,
    // independently synchronized queue.
    private val edges = ArrayDeque<CompanionFrame>(edgeCapacity)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var overflowed = false

    /** Caller holds this monitor. A full FIFO must close the TLS session. */
    private fun sendEdge(frame: CompanionFrame): Boolean {
        if (overflowed) return false
        if (edges.size < edgeCapacity) {
            edges.addLast(frame)
            signal.trySend(Unit)
            return true
        }
        overflowed = true
        edges.clear()
        gamepadMotion = null
        remoteMotion = null
        onOverflow() // The owner closes this TLS connection so the TV clears all held input.
        return false
    }
    private var gamepadMotion: CompanionFrame? = null
    private var remoteMotion: CompanionFrame? = null

    fun offerGamepad(frame: CompanionFrame, critical: Boolean): Boolean {
        require(when (frame.type) {
            "gamepad_claim", "gamepad_input", "gamepad_menu", "gamepad_command", "gamepad_release" -> true
            else -> false
        })
        if (critical || frame.type != "gamepad_input") {
            synchronized(this) {
                if (overflowed) return false
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
            if (overflowed) return false
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
            if (overflowed) return false
            if (input.action == "move" || input.action == "scroll") {
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

    /** A remote lease release must follow all queued input, never stale motion after release. */
    @Synchronized fun offerRemoteRelease(frame: CompanionFrame): Boolean {
        require(frame.type == "input_release")
        if (overflowed) return false
        remoteMotion?.let { if (!sendEdge(it)) return false }
        remoteMotion = null
        return sendEdge(frame)
    }

    private fun takeContinuousLocked(): CompanionFrame? {
        gamepadMotion?.let { gamepadMotion = null; return it }
        remoteMotion?.let { remoteMotion = null; return it }
        return null
    }

    // Checking the edge FIFO and taking a motion snapshot are one operation.
    // Otherwise a producer can insert DOWN followed by held-axis motion
    // between those two actions and the consumer sends the held state first.
    @Synchronized fun poll(normal: Channel<CompanionFrame>): CompanionFrame? {
        if (overflowed) return null
        return edges.pollFirst() ?: normal.tryReceive().getOrNull() ?: takeContinuousLocked()
    }

    private sealed interface Wake {
        data object Input : Wake
        data object Timeout : Wake
        data class Normal(val frame: CompanionFrame) : Wake
    }

    /**
     * The normal command stream is never delayed if gamepad control is idle. The wake-up channel
     * is conflated: one pending notification is enough however fast the phone sensor samples.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun receive(normal: Channel<CompanionFrame>, timeoutMs: Long): CompanionFrame? {
        poll(normal)?.let { return it }
        val start = System.nanoTime()
        while (true) {
            val remaining = timeoutMs.coerceAtLeast(0) - (System.nanoTime() - start) / 1_000_000L
            if (remaining <= 0L) return null
            // Timeout is a competing select clause, not a coroutine cancellation.
            // Once a normal receive wins, a timer cannot discard the accepted command.
            when (val wake = select<Wake> {
                signal.onReceive { Wake.Input }
                normal.onReceive { Wake.Normal(it) }
                onTimeout(remaining) { Wake.Timeout }
            }) {
                Wake.Input -> poll(normal)?.let { return it }
                is Wake.Normal -> return wake.frame
                Wake.Timeout -> return null
            }
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
                signal.onReceive { poll(normal) to false }
                normal.onReceive { it to false }
            }
            result.first?.let { return it to result.second }
        }
    }

    fun close() {
        synchronized(this) {
            overflowed = true
            edges.clear()
            gamepadMotion = null
            remoteMotion = null
        }
        signal.close()
    }
}
