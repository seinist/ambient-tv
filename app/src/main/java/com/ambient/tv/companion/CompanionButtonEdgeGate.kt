package com.ambient.tv.companion

import java.util.ArrayDeque

/**
 * Retains every actual DOWN/UP edge until a polling Libretro core can observe it.
 *
 * DOWN is immediate when idle. A brief DOWN must remain visible for at least minPressMs,
 * and consecutive taps get a distinct UP gap: the next DOWN is played after minGapMs.
 * Transitions are queued per button, not collapsed into one long hold.
 *
 * The session's hard-release path calls clear() instead of waiting for the gate.
 */
internal class CompanionButtonEdgeGate(
    private val minPressMs: Long = 35L,
    private val minGapMs: Long = 17L,
) {
    private val pressedAt = LongArray(16)
    private val releasedAt = LongArray(16) { Long.MIN_VALUE / 2L }
    private val pending = Array(16) { ArrayDeque<Boolean>() }
    private var desired = 0
    private var effective = 0

    fun update(buttons: Int, nowMs: Long): Int {
        val next = buttons and 0xffff
        val changed = desired xor next
        for (i in 0..15) {
            val bit = 1 shl i
            if (changed and bit == 0) continue
            // More than 32 unplayed transitions on one button means the emulator cannot
            // keep up. Reject rather than accumulate an unbounded input queue.
            require(pending[i].size < 32) { "Companion button edge backlog" }
            pending[i].addLast(next and bit != 0)
        }
        desired = next
        return settle(nowMs)
    }

    fun settle(nowMs: Long): Int {
        var visible = effective
        for (i in 0..15) {
            val bit = 1 shl i
            val events = pending[i]
            while (!events.isEmpty()) {
                val next = events.first
                val currentlyDown = visible and bit != 0
                if (next == currentlyDown) {
                    events.removeFirst()
                    continue
                }
                if (next) {
                    if (nowMs - releasedAt[i] < minGapMs) break
                    visible = visible or bit
                    pressedAt[i] = nowMs
                } else {
                    if (nowMs - pressedAt[i] < minPressMs) break
                    visible = visible and bit.inv()
                    releasedAt[i] = nowMs
                }
                events.removeFirst()
            }
        }
        effective = visible
        return visible
    }

    /** Time until the next queued UP or re-press (both are required to deliver distinct taps). */
    fun nextReleaseDelay(nowMs: Long): Long? {
        var earliest: Long? = null
        for (i in 0..15) {
            val event = pending[i].peekFirst() ?: continue
            val pressed = effective and (1 shl i) != 0
            val ready = if (event) releasedAt[i] + minGapMs else pressedAt[i] + minPressMs
            val delay = (ready - nowMs).coerceAtLeast(1L)
            earliest = if (earliest == null) delay else minOf(earliest, delay)
        }
        return earliest
    }

    fun clear() {
        pressedAt.fill(0L)
        releasedAt.fill(Long.MIN_VALUE / 2L)
        pending.forEach { it.clear() }
        desired = 0
        effective = 0
    }
}
