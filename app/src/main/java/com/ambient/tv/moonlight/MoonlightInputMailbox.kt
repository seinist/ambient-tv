package com.ambient.tv.moonlight

/** One drain consumer per stream generation. No lock is held across a native call. */
internal class MoonlightInputMailbox(private val capacity: Int = 256) {
    enum class Offer { START, QUEUED, OVERFLOW, CLOSED }
    private class Relative(val kind: String, var x: Int, var y: Int, val limit: Int,
                                val dispatch: (Int, Int) -> Unit)
    private class Pending(val key: Any?, var action: ((Long) -> Unit)? = null, val relative: Relative? = null, val queuedAt: Long = 0L) : () -> Unit {
        override fun invoke() {
            val callback = action
            if (callback != null) callback(queuedAt) else relative?.let { it.dispatch(it.x, it.y) }
        }
    }
    private val queue = ArrayDeque<Pending>()
    private var draining = false
    private var closed = false

    fun offer(key: Any?, action: () -> Unit): Offer = offerTimed(key, System.nanoTime()) { action() }

    @Synchronized fun offerTimed(key: Any?, queuedAt: Long, action: (Long) -> Unit): Offer {
        if (closed) return Offer.CLOSED
        if (key != null && queue.lastOrNull()?.key == key) {
            queue.last().action = action
            return Offer.QUEUED
        }
        return append(Pending(key, action, queuedAt = queuedAt))
    }

    /**
     * Relative motion is additive, not a replaceable state. Only neighboring reports in the
     * same direction may merge, and never across button edges, absolute position or scroll.
     * If the sum would exceed the native packet range, keep both reports in FIFO order.
     */
    @Synchronized fun offerRelative(kind: String, x: Int, y: Int, limit: Int,
                                   dispatch: (Int, Int) -> Unit): Offer {
        if (closed) return Offer.CLOSED
        require(limit in 1..32767 && x in -limit..limit && y in -limit..limit)
        if (x == 0 && y == 0) return Offer.QUEUED
        val old = queue.lastOrNull()?.relative
        fun compatible(a: Int, b: Int) = a == 0 || b == 0 || (a > 0) == (b > 0)
        if (old != null && old.kind == kind && old.limit == limit &&
            compatible(old.x, x) && compatible(old.y, y)) {
            val sumX = old.x + x
            val sumY = old.y + y
            if (sumX in -limit..limit && sumY in -limit..limit) {
                // Still owned by the queue; preserve the oldest dispatch timestamp.
                old.x = sumX
                old.y = sumY
                return Offer.QUEUED
            }
        }
        return append(Pending(null, relative = Relative(kind, x, y, limit, dispatch)))
    }

    private fun append(pending: Pending): Offer {
        if (queue.size >= capacity) {
            closed = true
            queue.clear()
            return Offer.OVERFLOW
        }
        queue.addLast(pending)
        if (draining) return Offer.QUEUED
        draining = true
        return Offer.START
    }

    @Synchronized fun take(): (() -> Unit)? {
        val next = queue.removeFirstOrNull()
        if (next == null) draining = false
        return next
    }
    @Synchronized fun close() { closed = true; queue.clear() }
    @Synchronized fun depth(): Int = queue.size
}

/** Preserve button, trigger and axis-neutral/sign transitions even when analog reports coalesce. */
internal data class MoonlightPadUpdateKey(
    val remote: Boolean, val port: Int, val mask: Int, val buttons: Int,
    val triggerState: Int, val axisState: Int, val capabilities: MoonlightPadCapabilities?,
) {
    constructor(remote: Boolean, port: Int, mask: Int, buttons: Int,
                lt: Byte, rt: Byte, lx: Short, ly: Short, rx: Short, ry: Short,
                capabilities: MoonlightPadCapabilities?) :
        this(remote, port, mask, buttons, (if (lt != 0.toByte()) 1 else 0) or
            (if (rt != 0.toByte()) 2 else 0), sign(lx) or (sign(ly) shl 2) or
            (sign(rx) shl 4) or (sign(ry) shl 6), capabilities)
    companion object {
        private fun sign(value: Short): Int = when { value < 0 -> 1; value > 0 -> 2; else -> 0 }
    }
}
