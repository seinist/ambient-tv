package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation

/**
 * The browse queue's currentId represents *committed playback*. During rapid zapping the
 * requested station advances independently, so repeated keys don't keep choosing the same
 * neighbor of the old stream. No intermediate target is committed or counted as played.
 */
internal class RadioZapCursor(private val startingStationId: String) {
    private var stepsFromStart = 0

    private fun baseIndex(queue: RadioPlaybackQueue.State): Int? =
        queue.stations.indexOfFirst { it.id == startingStationId }.takeIf { it >= 0 }

    fun requestedIndex(queue: RadioPlaybackQueue.State): Int? =
        baseIndex(queue)?.let { it + stepsFromStart }

    fun target(queue: RadioPlaybackQueue.State): RadioStation? =
        requestedIndex(queue)?.let(queue.stations::getOrNull)

    fun needsPage(queue: RadioPlaybackQueue.State): Boolean =
        requestedIndex(queue)?.let { it >= queue.stations.size && queue.source?.hasMore == true } == true

    fun canMove(queue: RadioPlaybackQueue.State, direction: Int): Boolean {
        if (direction != 1 && direction != -1) return false
        val index = requestedIndex(queue) ?: return false
        val next = index + direction
        if (next < 0) return false
        if (next < queue.stations.size) return true
        // Once the cursor is past the last loaded row, previous still works while loading,
        // including when the API has just reported that there are no more pages.
        if (direction < 0 && next < index) return true
        return queue.source?.hasMore == true &&
            next - queue.stations.size < MAX_RADIO_ZAP_PENDING_STATIONS
    }

    fun move(queue: RadioPlaybackQueue.State, direction: Int): Boolean {
        if (!canMove(queue, direction)) return false
        stepsFromStart += direction
        return true
    }

    /** A final short/empty page (or API failure) cannot leave the player stuck on Loading. */
    fun clampToLoaded(queue: RadioPlaybackQueue.State) {
        val base = baseIndex(queue) ?: return
        val last = (queue.stations.size - 1).coerceAtLeast(0)
        stepsFromStart = (base + stepsFromStart).coerceIn(0, last) - base
    }
}

/** Safety bound for an exceptionally long held key while the catalog is unreachable. */
private const val MAX_RADIO_ZAP_PENDING_STATIONS = 512

/** Adjacent events inside this interval select only the final stream, without extra network starts. */
internal const val RADIO_ZAP_SETTLE_MS = 180L
