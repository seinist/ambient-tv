package com.ambient.tv.vpn

/**
 * Sliding anti-replay window for transport counters (RFC 6479 style). A fixed bitmap replaces a
 * hash set that was pruned on every packet; marking and checking are O(1) without allocation.
 * Not thread-safe; [WgSession] guards it per keypair.
 */
internal class WgReplayWindow(private val size: Long = 2048L) {
    private val bits = LongArray((size / 64).toInt())
    private var highest = -1L

    fun canAccept(counter: Long): Boolean {
        if (counter < 0) return false
        if (counter > highest) return true
        if (highest - counter >= size) return false
        return !isSet(counter)
    }

    fun markAccepted(counter: Long): Boolean {
        if (!canAccept(counter)) return false
        if (counter > highest) {
            val advance = if (highest < 0) size else counter - highest
            if (advance >= size) {
                bits.fill(0L)
            } else {
                // Clear the slots the window slides over; they belong to counters now too old.
                var cleared = highest + 1
                while (cleared <= counter) {
                    clear(cleared)
                    cleared++
                }
            }
            highest = counter
        }
        set(counter)
        return true
    }

    private fun slot(counter: Long) = ((counter % size) / 64).toInt()
    private fun mask(counter: Long) = 1L shl ((counter % size) % 64).toInt()
    private fun isSet(counter: Long) = bits[slot(counter)] and mask(counter) != 0L
    private fun set(counter: Long) { bits[slot(counter)] = bits[slot(counter)] or mask(counter) }
    private fun clear(counter: Long) { bits[slot(counter)] = bits[slot(counter)] and mask(counter).inv() }
}
