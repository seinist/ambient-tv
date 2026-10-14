package com.ambient.tv.ui.player.backend

import com.ambient.tv.network.isHomeNetworkHost
import java.net.URI

/** mpv_end_file_reason: EOF=0, STOP=2, QUIT=3, ERROR=4, REDIRECT=5. */
internal fun mpvTerminalEnd(reason: Int, error: Int, eof: Boolean): Boolean =
    reason == 0 || reason == 4 || (reason == -1 && (error < 0 || eof))

/**
 * mpv's extra stream cache stalls Plex range requests on some LAN devices (SHIELD), so it stays off
 * for servers in the home network. Remote servers (WAN, reverse proxy, public plex.direct) need it:
 * without read-ahead every throughput dip becomes a visible rebuffer.
 */
internal fun mpvUsesNetworkCache(url: String, forced: Boolean): Boolean {
    if (forced) return true
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host ?: return false
    return !isHomeNetworkHost(host)
}

/**
 * ffmpeg options for network streams. mpv already reconnects plain premature ends for a few
 * seconds; this also covers network errors (Wi-Fi switch, server restart) and waits up to 30 s,
 * so an outage does not end the stream as if the file were complete.
 */
internal const val MPV_NETWORK_RECONNECT = "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=30"

internal fun mpvNeedsReconnect(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("edl://", ignoreCase = true)

/**
 * A normal end of file well before the known duration is a broken stream, not the end of the
 * title: the connection stayed down longer than the reconnect window. Short clips and streams
 * without a duration (live) are not judged.
 */
internal fun mpvEndedEarly(positionMs: Long, durationMs: Long): Boolean =
    durationMs >= 60_000L && positionMs in 1 until durationMs - 15_000L
