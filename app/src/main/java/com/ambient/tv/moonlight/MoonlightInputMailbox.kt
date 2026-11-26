package com.ambient.tv.moonlight

/** One drain consumer per stream generation. No lock is held across a native call. */
internal class MoonlightInputMailbox(private val capacity: Int = 256) {
    enum class Offer { START, QUEUED, OVERFLOW, CLOSED }
    private data class Pending(val key: Any?, val action: () -> Unit)
    private val queue = ArrayDeque<Pending>()
    private var draining = false
    private var closed = false

    @Synchronized fun offer(key: Any?, action: () -> Unit): Offer {
        if (closed) return Offer.CLOSED
        if (key != null && queue.lastOrNull()?.key == key) {
            queue.removeLast()
            queue.addLast(Pending(key, action))
            return Offer.QUEUED
        }
        if (queue.size >= capacity) {
            closed = true
            queue.clear()
            return Offer.OVERFLOW
        }
        queue.addLast(Pending(key, action))
        if (draining) return Offer.QUEUED
        draining = true
        return Offer.START
    }

    @Synchronized fun take(): (() -> Unit)? {
        val next = queue.removeFirstOrNull()
        if (next == null) draining = false
        return next?.action
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
