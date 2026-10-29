package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioPlaybackQueue @Inject constructor() {
    @Volatile private var state = State()

    data class State(
        val stations: List<RadioStation> = emptyList(),
        val currentId: String? = null,
    )

    @Synchronized
    fun replace(stations: List<RadioStation>, currentId: String) {
        val unique = stations
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        val normalized = if (unique.any { it.id == currentId }) unique else unique + stations.firstOrNull { it.id == currentId }.orEmpty()
        state = State(normalized, currentId)
    }

    @Synchronized
    fun select(currentId: String) {
        if (state.stations.any { it.id == currentId }) state = state.copy(currentId = currentId)
    }

    fun snapshot(): State = state

    fun previous(): RadioStation? = adjacent(-1)
    fun next(): RadioStation? = adjacent(1)

    private fun adjacent(delta: Int): RadioStation? {
        val current = state
        val index = current.stations.indexOfFirst { it.id == current.currentId }
        if (index < 0) return null
        return current.stations.getOrNull(index + delta)
    }

    private fun RadioStation?.orEmpty(): List<RadioStation> = if (this == null) emptyList() else listOf(this)
}
