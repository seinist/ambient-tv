package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Browse cards need the selected station and play intent, not the player's 500 ms timeline. */
internal data class RadioBrowsePlayback(val stationId: String? = null, val wantsToPlay: Boolean = false)

internal fun BackgroundPlaybackSession.Snapshot.radioBrowsePlayback(): RadioBrowsePlayback =
    if (metadata?.source == "radio") RadioBrowsePlayback(metadata.mediaId, wantsToPlay)
    else RadioBrowsePlayback()

internal fun Flow<BackgroundPlaybackSession.Snapshot>.radioBrowsePlayback(): Flow<RadioBrowsePlayback> =
    map { it.radioBrowsePlayback() }.distinctUntilChanged()

/** Radio publishes immutable station lists; index each new list once, not on every D-pad move. */
internal class RadioStationIndex {
    private var indexedStations: List<RadioStation>? = null
    private var indices: Map<String, Int> = emptyMap()

    fun indexOf(stations: List<RadioStation>, stationId: String): Int {
        if (stations !== indexedStations) {
            indices = buildMap(stations.size) {
                stations.forEachIndexed { index, station ->
                    // Preserve indexOfFirst semantics even for a provider's duplicate station ID.
                    if (station.id !in this) put(station.id, index)
                }
            }
            indexedStations = stations
        }
        return indices[stationId] ?: -1
    }
}
