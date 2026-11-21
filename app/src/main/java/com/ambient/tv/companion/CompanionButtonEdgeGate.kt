package com.ambient.tv.companion

/**
 * Libretro cores poll buttons once per emulation frame. An immediate network DOWN followed by UP
 * can arrive between two polls, so expose DOWN without delay but hold an ultra-short press until
 * the next couple of 60Hz frames have had a chance to read it.
 *
 * This changes only the TV's effective state, never packet order. Explicit disconnect, menu and
 * pause release bypass this gate and clear all ports immediately.
 */
internal class CompanionButtonEdgeGate(private val minPressMs: Long = 35L) {
    private val pressedAt = LongArray(16)
    private var desired = 0
    private var effective = 0

    fun update(buttons: Int, nowMs: Long): Int {
        val next = buttons and 0xffff
        val pressed = next and desired.inv()
        for (i in 0..15) if (pressed and (1 shl i) != 0) pressedAt[i] = nowMs
        desired = next
        return settle(nowMs)
    }

    fun settle(nowMs: Long): Int {
        var result = desired
        for (i in 0..15) {
            val bit = 1 shl i
            if (effective and bit != 0 && desired and bit == 0 &&
                nowMs - pressedAt[i] < minPressMs) result = result or bit
        }
        effective = result
        return result
    }

    /** Next scheduled release; null means there is no delayed button edge. */
    fun nextReleaseDelay(nowMs: Long): Long? {
        var next: Long? = null
        for (i in 0..15) {
            val bit = 1 shl i
            if (effective and bit != 0 && desired and bit == 0) {
                val remaining = (pressedAt[i] + minPressMs - nowMs).coerceAtLeast(1L)
                next = if (next == null) remaining else minOf(next, remaining)
            }
        }
        return next
    }

    fun clear() {
        pressedAt.fill(0L)
        desired = 0
        effective = 0
    }
}
