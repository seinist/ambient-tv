package com.ambient.tv.ui.home

import com.ambient.tv.data.radio.RadioStation

/** The exact radio Home row that was clicked, including its displayed tile order. */
data class HomeRadioPlaybackSelection(
    val rowId: String,
    val stationId: String,
    val stationIds: List<String>,
    val cachedStations: List<RadioStation>,
)

/** Resolve by UUID, never by the order of an asynchronously refreshed catalog response. */
internal fun homeRadioPlaybackQueue(
    selection: HomeRadioPlaybackSelection,
    fetched: List<RadioStation>,
    clicked: RadioStation,
): List<RadioStation> {
    val byId = (selection.cachedStations + fetched + clicked).associateBy { it.id }
    return selection.stationIds.distinct().mapNotNull(byId::get)
        .ifEmpty { listOf(clicked) }
}
