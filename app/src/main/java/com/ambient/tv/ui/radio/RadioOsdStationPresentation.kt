package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.PlaybackMetadata

/**
 * The OSD chrome never disappears while Radio Browser changes stations.
 * Only metadata belonging to the intended station is allowed into its header.
 */
internal data class RadioOsdStationPresentation(
    val stationId: String,
    val station: RadioStation?,
    val stationName: String,
    val nowPlaying: String?,
    val artworkUrl: String?,
    val artworkFallbackUrls: List<String> = emptyList(),
    val artworkHeaders: Map<String, String> = emptyMap(),
    val switching: Boolean = false,
)

internal const val RADIO_OSD_LOADING_ID = "radio:loading-next-station"

/**
 * A pending station wins over the old playback snapshot, including artwork and ICY text.
 * When fetching the next page there is not yet a target: show a neutral, localized status
 * without leaking the previous station's metadata. Keep the same OSD structure throughout.
 */
internal fun radioOsdStationPresentation(
    metadata: PlaybackMetadata?,
    selectedStation: RadioStation?,
    pendingStation: RadioStation?,
    switching: Boolean,
    loadingLabel: String,
): RadioOsdStationPresentation? {
    if (switching) {
        return if (pendingStation != null) {
            RadioOsdStationPresentation(
                stationId = pendingStation.id,
                station = pendingStation,
                stationName = pendingStation.name,
                nowPlaying = null,
                artworkUrl = pendingStation.artworkUrl,
                switching = true,
            )
        } else {
            RadioOsdStationPresentation(
                stationId = RADIO_OSD_LOADING_ID,
                station = null,
                stationName = loadingLabel,
                nowPlaying = null,
                artworkUrl = null,
                switching = true,
            )
        }
    }
    if (metadata?.source != "radio") return null
    return RadioOsdStationPresentation(
        stationId = metadata.mediaId,
        station = selectedStation?.takeIf { it.id == metadata.mediaId },
        stationName = metadata.title,
        nowPlaying = metadata.subtitle,
        artworkUrl = metadata.artworkUrl,
        artworkFallbackUrls = metadata.artworkFallbackUrls,
        artworkHeaders = metadata.artworkHeaders,
    )
}

/**
 * Whether the details dialog has a station to show. While switching, the requested station is
 * shown (without song text, which belongs to the old stream), so the Details button stays on the
 * OSD during zapping; only the neutral "loading next station" placeholder has no details.
 */
internal fun radioOsdHasDetail(shown: RadioOsdStationPresentation): Boolean =
    !shown.switching || shown.station != null
