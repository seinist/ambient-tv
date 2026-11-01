package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation

/**
 * The playback queue's currentId is the *committed* stream. Repeated keys advance a separate
 * selection by station UUID, and only the final resolved choice changes the playing stream.
 * Requested-but-unloaded rows use an offset past the last known UUID.
 */
internal class RadioZapCursor(startingStationId: String) {
    private var selectedId: String = startingStationId
    private var unloadedSteps = 0

    fun requestedIndex(queue: RadioPlaybackQueue.State): Int? =
        queue.stations.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
            ?.let { it + unloadedSteps }

    fun target(queue: RadioPlaybackQueue.State): RadioStation? =
        requestedIndex(queue)?.let(queue.stations::getOrNull)

    fun needsPage(queue: RadioPlaybackQueue.State): Boolean =
        requestedIndex(queue)?.let { it >= queue.stations.size && queue.source?.hasMore == true } == true

    /**
     * Once a new page contains the requested station, remember its ID. Later sorting or
     * deduplication may insert other stations before it, but must never change the choice.
     */
    fun stabilize(queue: RadioPlaybackQueue.State) {
        if (unloadedSteps == 0) return
        target(queue)?.let {
            selectedId = it.id
            unloadedSteps = 0
        }
    }

    fun canMove(queue: RadioPlaybackQueue.State, direction: Int): Boolean {
        if (direction != 1 && direction != -1) return false
        val index = requestedIndex(queue) ?: return false
        val next = index + direction
        if (next < 0) return false
        if (next < queue.stations.size) return true
        // The user can still reverse back toward loaded stations if pagination fails.
        if (direction < 0) return true
        return queue.source?.hasMore == true &&
            next - queue.stations.size < MAX_RADIO_ZAP_PENDING_STATIONS
    }

    fun move(queue: RadioPlaybackQueue.State, direction: Int): Boolean {
        stabilize(queue)
        if (!canMove(queue, direction)) return false
        val index = checkNotNull(requestedIndex(queue)) + direction
        val known = queue.stations.getOrNull(index)
        if (known != null) {
            selectedId = known.id
            unloadedSteps = 0
        } else {
            // Moving backward through unresolved slots decrements the offset; moving
            // forward accumulates every step even if the next API page is still loading.
            unloadedSteps += direction
        }
        return true
    }

    /** Short/empty last page (or fetch error) cannot leave a permanent Loading OSD. */
    fun clampToLoaded(queue: RadioPlaybackQueue.State) {
        val last = queue.stations.lastOrNull() ?: return
        selectedId = last.id
        unloadedSteps = 0
    }
}

private const val MAX_RADIO_ZAP_PENDING_STATIONS = 512

/** D-pad repeat bursts select only the final station; no intermediate network starts. */
internal const val RADIO_ZAP_SETTLE_MS = 180L
