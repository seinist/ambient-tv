package com.ambient.tv.companion

/**
 * Bounded, allocation-free per-sample collection of phone->TV->phone round-trip latency.
 * Reporting p95 instead of a single last sample makes intermittent Wi-Fi jitter visible.
 *
 * This measures transport/queue/TV-command latency, not touch-to-pixel end-to-end response.
 */
internal class CompanionInputLatencyWindow(private val capacity: Int = 64) {
    init { require(capacity in 1..256) }
    private val samples = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun record(elapsedMs: Long) {
        samples[next] = elapsedMs.coerceIn(0, 60_000)
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun p95Ms(): Long? {
        if (count == 0) return null
        val sorted = samples.copyOf(count)
        sorted.sort()
        return sorted[((count * 95 + 99) / 100 - 1).coerceIn(0, count - 1)]
    }
}
