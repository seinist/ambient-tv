package com.ambient.tv.ui.radio

/**
 * Match the Live-TV radio OSD: station/service are context, now-playing is the large title.
 * Stream metadata is optional and not a programme schedule. Never put codec or bitrate here.
 */
internal data class RadioHeaderPresentation(
    val title: String,
    val subtitle: String?,
    val contextLine: String,
)

internal fun radioHeaderPresentation(
    stationName: String,
    nowPlaying: String?,
    serviceName: String,
): RadioHeaderPresentation {
    val station = stationName.trim().ifBlank { serviceName }
    val playing = nowPlaying?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(station, ignoreCase = true) }

    if (playing == null) {
        return RadioHeaderPresentation(
            title = station,
            subtitle = null,
            contextLine = serviceName,
        )
    }

    // Most ICY streams use "Artist - Title"; keep the full metadata untouched if ambiguous.
    val parts = playing.split(Regex("""\s+[-–—]\s+"""), limit = 2)
    val artist = parts.getOrNull(0)?.trim()?.takeIf { parts.size == 2 && it.isNotBlank() }
    val track = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    return RadioHeaderPresentation(
        title = if (artist != null && track != null) track else playing,
        subtitle = if (artist != null && track != null) artist else null,
        contextLine = station,
    )
}
