package com.ambient.tv.ui.player

/** GL-thread gate: frame callbacks announce availability, not a queue of distinct buffers. */
internal class SdrHdrFrameGate {
    private var pending = false
    private var scheduled = false
    private var lastTimestampNs: Long? = null

    fun available(): Boolean {
        pending = true
        if (scheduled) return false
        scheduled = true
        return true
    }

    fun consume(connected: Boolean): Boolean {
        scheduled = false
        if (!connected || !pending) return false
        pending = false
        return true
    }

    /** Zero/unknown timestamps cannot identify duplicates; a backwards seek remains valid. */
    fun acceptTimestamp(timestampNs: Long): Boolean {
        if (timestampNs <= 0L) return true
        if (lastTimestampNs == timestampNs) return false
        lastTimestampNs = timestampNs
        return true
    }

    fun discontinuity() { lastTimestampNs = null }
}
