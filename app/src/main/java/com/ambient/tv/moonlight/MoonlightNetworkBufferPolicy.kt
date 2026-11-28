package com.ambient.tv.moonlight

/** Session-local hysteresis. ENet RTT variance is a pressure signal, not RTP jitter itself. */
internal class MoonlightNetworkBufferPolicy(private val pacing: String) {
    data class Targets(val audioReorderMs: Int = 10, val videoBufferTier: Int = 0)
    private var current = Targets()
    private var pressureSamples = 0
    private var stableSamples = 0

    /** Called once per second; missing RTT never counts as a stable sample. */
    fun sample(rttVarianceMs: Int?, networkPoor: Boolean, missing: Long, received: Long): Targets {
        val variance = rttVarianceMs?.takeIf { it >= 0 }
        val loss = missing > 2 && missing.toDouble() / (missing.toDouble() + received.coerceAtLeast(0L)) > 0.05
        val pressure = networkPoor || loss || (variance != null && variance >= 8)
        val stable = variance != null && variance <= 3 && !networkPoor && !loss && missing == 0L && received > 0
        if (pressure) {
            stableSamples = 0
            if (++pressureSamples >= 2) {
                val desired = ((variance ?: 8).coerceIn(0, 30) * 2 + 6).coerceIn(14, 30)
                current = Targets(
                    maxOf(current.audioReorderMs, desired).coerceAtMost(30),
                    (current.videoBufferTier + 1).coerceAtMost(2))
                pressureSamples = 0
            }
        } else if (stable) {
            pressureSamples = 0
            if (++stableSamples >= 10) {
                val floor = when (pacing) { "latency" -> 4; "smooth" -> 10; else -> 6 }
                current = Targets(
                    (current.audioReorderMs - 3).coerceAtLeast(floor),
                    (current.videoBufferTier - 1).coerceAtLeast(0))
                stableSamples = 0
            }
        } else {
            pressureSamples = 0
            stableSamples = 0
        }
        return current
    }
}
