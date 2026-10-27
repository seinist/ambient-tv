package com.ambient.tv.livetv

/**
 * Art for a Live TV row; [logo] art is a channel logo and is fitted, not cropped. [external] art
 * (TMDb) is not on the media server and must be loaded without the server's auth headers.
 */
data class LiveTvArt(val url: String, val logo: Boolean = false, val external: Boolean = false) {
    fun headers(serverHeaders: Map<String, String>): Map<String, String> = if (external) emptyMap() else serverHeaders
}

/** Key for title-based art lookups (TMDb); case and surrounding blanks do not matter. */
fun liveTvArtKey(title: String): String = title.trim().lowercase(java.util.Locale.ROOT)

/**
 * A recording's or timer's art: its own image, its fanart, the EPG programme it was made from,
 * art found by title, and last the channel logo. TVHeadend DVR entries usually carry no image
 * of their own while the guide (or TMDb) has one.
 */
fun liveTvRecordingArt(
    entry: LiveTvRecording,
    events: List<LiveTvProgramme>,
    channels: List<LiveTvChannel>,
    titleArt: Map<String, String> = emptyMap(),
): LiveTvArt? {
    (entry.imageUrl ?: entry.fanartUrl)?.let { return LiveTvArt(it) }
    val event = entry.broadcastEventId?.let { id -> events.firstOrNull { it.eventId == id } }
        ?: events.firstOrNull {
            it.channelUuid == entry.channelUuid && it.startEpochSeconds == entry.startEpochSeconds && it.title == entry.title
        }
    event?.imageUrl?.let { return LiveTvArt(it) }
    titleArt[liveTvArtKey(entry.title)]?.let { return LiveTvArt(it, external = true) }
    return channels.firstOrNull { it.uuid == entry.channelUuid }?.iconUrl?.let { LiveTvArt(it, logo = true) }
}

/** A programme's art: its own image, art found by title, then the channel logo. */
fun liveTvProgrammeArt(event: LiveTvProgramme, channels: List<LiveTvChannel>, titleArt: Map<String, String> = emptyMap()): LiveTvArt? {
    event.imageUrl?.let { return LiveTvArt(it) }
    titleArt[liveTvArtKey(event.title)]?.let { return LiveTvArt(it, external = true) }
    return channels.firstOrNull { it.uuid == event.channelUuid }?.iconUrl?.let { LiveTvArt(it, logo = true) }
}

/** A series timer's art: its own, the guide's for the same title, art found by title, then the channel logo. */
fun liveTvSeriesArt(
    entry: LiveTvSeriesTimer,
    events: List<LiveTvProgramme>,
    channels: List<LiveTvChannel>,
    titleArt: Map<String, String> = emptyMap(),
): LiveTvArt? {
    entry.imageUrl?.let { return LiveTvArt(it) }
    val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.name
    val key = liveTvArtKey(title)
    events.firstOrNull { it.imageUrl != null && liveTvArtKey(it.title) == key }?.imageUrl?.let { return LiveTvArt(it) }
    titleArt[key]?.let { return LiveTvArt(it, external = true) }
    return channels.firstOrNull { it.name == entry.channelName || it.uuid == entry.channelName }?.iconUrl?.let { LiveTvArt(it, logo = true) }
}
