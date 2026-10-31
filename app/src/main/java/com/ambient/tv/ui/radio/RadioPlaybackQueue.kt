package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.data.radio.RadioSearchPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captured when a station starts from Radio Browser. The Player requests more only from this
 * original facet/search; it cannot spill into Popular, Favorites, or another category.
 */
data class RadioBrowseQueueSource(
    val focusScope: RadioFocusScope,
    val query: String? = null,
    val countryCode: String? = null,
    val language: String? = null,
    val tag: String? = null,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    /** Continue alphabetized result pages in the same direction as the browse view. */
    val sortDescending: Boolean = false,
)

@Singleton
class RadioPlaybackQueue @Inject constructor() {
    @Volatile private var state = State()

    data class State(
        val stations: List<RadioStation> = emptyList(),
        val currentId: String? = null,
        val source: RadioBrowseQueueSource? = null,
        val returnPending: Boolean = false,
    )

    @Synchronized
    fun replace(stations: List<RadioStation>, currentId: String, source: RadioBrowseQueueSource? = null) {
        val unique = stations
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        val normalized = if (unique.any { it.id == currentId }) unique else unique + stations.firstOrNull { it.id == currentId }.orEmpty()
        state = State(normalized, currentId, source, returnPending = source != null)
    }

    @Synchronized
    fun select(currentId: String) {
        if (state.stations.any { it.id == currentId }) state = state.copy(
            currentId = currentId, returnPending = state.source != null,
        )
    }

    fun snapshot(): State = state

    /** Append one API page without replacing the running queue or changing its current UUID. */
    @Synchronized
    fun appendPage(source: RadioBrowseQueueSource, page: RadioSearchPage): Boolean {
        if (state.source != source || page.offset != source.nextOffset) return false
        val seen = state.stations.mapTo(HashSet()) { it.id }
        state = state.copy(
            stations = state.stations + page.items.filter { it.id.isNotBlank() && seen.add(it.id) },
            source = source.copy(nextOffset = page.offset + page.limit, hasMore = page.hasMore),
        )
        return true
    }

    /** Scoped, one-shot handoff to the browse view after station changes in the player. */
    @Synchronized
    fun consumeReturn(scope: RadioFocusScope): State? {
        val saved = state
        if (!saved.returnPending || saved.currentId == null || saved.source?.focusScope != scope) return null
        state = saved.copy(returnPending = false)
        return saved
    }

    fun current(): RadioStation? {
        val current = state
        return current.stations.firstOrNull { it.id == current.currentId }
    }

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
