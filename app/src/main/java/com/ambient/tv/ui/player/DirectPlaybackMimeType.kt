package com.ambient.tv.ui.player

import com.ambient.tv.domain.playback.PlaybackMode
import java.util.Locale

/**
 * Cast/DLNA proxies may hide a manifest's extension. Carry its known format into Media3's source
 * selection, but never apply the original container to a server's remuxed/transcoded response.
 * Progressive formats still use the player's normal content detection.
 */
internal fun directPlaybackMimeType(container: String?, mode: PlaybackMode): String? {
    if (mode != PlaybackMode.DIRECT_PLAY) return null
    return when (container?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
        "m3u8", "hls", "application/x-mpegurl", "application/vnd.apple.mpegurl",
        "audio/mpegurl", "audio/x-mpegurl" -> "application/x-mpegURL"
        "mpd", "dash", "application/dash+xml" -> "application/dash+xml"
        else -> null
    }
}
