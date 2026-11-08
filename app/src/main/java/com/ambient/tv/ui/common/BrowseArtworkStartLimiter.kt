package com.ambient.tv.ui.common

/**
 * Admission control for uncached artwork started by browse cards.
 *
 * Lazy grids/rows can compose many cards in one frame. Coil already limits decode concurrency,
 * but creating every request together still produces a short fetch/decode/composition burst.
 * Admit a small group per display frame instead. A quiet gap starts a fresh burst so ordinary
 * incremental scrolling does not inherit old queue depth.
 */
internal const val BROWSE_ARTWORK_STARTS_PER_FRAME = 4
internal const val BROWSE_ARTWORK_FRAME_MS = 16L
internal const val BROWSE_ARTWORK_BURST_RESET_MS = 48L
internal const val BROWSE_ARTWORK_MAX_DELAY_MS = 96L

internal class BrowseArtworkStartLimiter {
    private var lastRequestMs = Long.MIN_VALUE
    private var requestsInBurst = 0

    @Synchronized
    fun delayMs(nowMs: Long): Long {
        if (lastRequestMs == Long.MIN_VALUE || nowMs < lastRequestMs ||
            nowMs - lastRequestMs > BROWSE_ARTWORK_BURST_RESET_MS
        ) {
            requestsInBurst = 0
        }
        val wave = requestsInBurst / BROWSE_ARTWORK_STARTS_PER_FRAME
        requestsInBurst++
        lastRequestMs = nowMs
        return (wave * BROWSE_ARTWORK_FRAME_MS).coerceAtMost(BROWSE_ARTWORK_MAX_DELAY_MS)
    }
}

internal val SharedBrowseArtworkStartLimiter = BrowseArtworkStartLimiter()
