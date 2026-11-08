package com.ambient.tv.ui.radio

/** The exact station and all player-loaded pages are available before a Lazy state is created. */
internal fun prepareRadioPlaybackReturn(
    before: RadioUiState,
    returned: RadioPlaybackQueue.State?,
    requestedSection: RadioSection = before.section,
): RadioUiState? {
    if (returned == null || !returned.returnPending || returned.currentId == null) return null
    val source = returned.source ?: return null
    if (before.section != requestedSection || source.focusScope != before.focusScope()) return null
    val merged = radioAppendPagedStations(before.stations, returned.stations)
    if (merged.none { it.id == returned.currentId }) return null
    val stations = if (radioStationAlphabetAllowed(before.section, before.selectedFacet, before.submittedQuery)) {
        radioAlphabeticalStations(merged, before.sortDescending)
    } else merged
    return before.copy(
        stations = stations,
        nextOffset = source.nextOffset,
        hasMore = source.hasMore,
        loading = false,
        loadingMore = false,
        contentReady = true,
        error = false,
        playbackReturnRevision = returned.returnRevision,
        returnFocusRevision = before.returnFocusRevision + if (before.playbackReturnRevision == returned.returnRevision) 0 else 1,
    )
}
