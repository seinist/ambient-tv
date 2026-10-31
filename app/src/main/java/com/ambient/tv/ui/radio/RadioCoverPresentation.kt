package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import com.ambient.tv.ui.player.PlaybackMetadata

/** Stable cover identity: ICY/song-title updates do not restart the artwork transition. */
internal data class RadioCoverPresentation(
    val stationId: String,
    val stationName: String,
    val artworkUrl: String?,
    val fallbackUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
) {
    val hasArtwork: Boolean
        get() = !artworkUrl.isNullOrBlank() || fallbackUrls.any(String::isNotBlank)
}

/**
 * Preview a known next/previous station immediately while its stream is connecting.
 * Once playback starts, the metadata for that same station takes over without a
 * second visual transition. Never use the outgoing station as the pending target.
 */
internal fun radioCoverPresentation(
    metadata: PlaybackMetadata?,
    pendingStation: RadioStation?,
): RadioCoverPresentation? = when {
    pendingStation != null -> RadioCoverPresentation(
        stationId = pendingStation.id,
        stationName = pendingStation.name,
        artworkUrl = pendingStation.artworkUrl,
    )
    metadata?.source == "radio" -> RadioCoverPresentation(
        stationId = metadata.mediaId,
        stationName = metadata.title,
        artworkUrl = metadata.artworkUrl,
        fallbackUrls = metadata.artworkFallbackUrls,
        headers = metadata.artworkHeaders,
    )
    else -> null
}
