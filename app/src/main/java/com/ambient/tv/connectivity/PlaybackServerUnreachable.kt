package com.ambient.tv.connectivity

import androidx.media3.common.PlaybackException
import com.ambient.tv.data.emby.EmbyBackend
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.dlna.DlnaKeys
import com.ambient.tv.data.jellyfin.JellyfinBackend
import com.ambient.tv.ui.common.MediaSystem
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Local media backends (Plex / Jellyfin / Emby / TVHeadend / Files) can lose the server mid-stream.
 * YouTube and Spotify keep their own error UX and must not be remapped here.
 */
object PlaybackServerUnreachable {
    fun mediaSystemForRatingKey(ratingKey: String): MediaSystem? = when {
        com.ambient.tv.data.shared.SharedMediaRepository.owns(ratingKey) -> null
        FileKeys.owns(ratingKey) -> MediaSystem.FILES
        DlnaKeys.owns(ratingKey) -> MediaSystem.DLNA
        EmbyBackend.owns(ratingKey) -> MediaSystem.EMBY
        JellyfinBackend.owns(ratingKey) -> MediaSystem.JELLYFIN
        ratingKey.startsWith("tvh:", ignoreCase = true) ||
            ratingKey.startsWith("tvheadend:", ignoreCase = true) -> MediaSystem.TVHEADEND
        ratingKey.startsWith("yt:", ignoreCase = true) ||
            ratingKey.startsWith("youtube:", ignoreCase = true) -> MediaSystem.YOUTUBE
        ratingKey.startsWith("spotify:", ignoreCase = true) -> MediaSystem.SPOTIFY
        ratingKey.isNotBlank() -> MediaSystem.PLEX
        else -> null
    }

    fun appliesToLocalMedia(ratingKey: String): Boolean = when (mediaSystemForRatingKey(ratingKey)) {
        MediaSystem.PLEX, MediaSystem.JELLYFIN, MediaSystem.EMBY, MediaSystem.DLNA, MediaSystem.TVHEADEND, MediaSystem.FILES -> true
        else -> false
    }

    fun isNetworkish(error: PlaybackException): Boolean {
        // Media3 IO / timeout codes are 1xxx–2xxx; prefer named constants when present.
        val code = error.errorCode
        if (code in NETWORK_ERROR_CODES || code in 2000..2008 || code == 1003) return true
        return isNetworkish(error.cause, error.message)
    }

    fun isNetworkish(cause: Throwable?, message: String?): Boolean {
        if (looksNetworkMessage(message)) return true
        var current = cause
        var depth = 0
        while (current != null && depth < 8) {
            when (current) {
                is UnknownHostException,
                is ConnectException,
                is NoRouteToHostException,
                is SocketTimeoutException,
                is TimeoutException,
                is EOFException -> return true
                is SocketException -> return true
                is IOException -> {
                    if (looksNetworkMessage(current.message)) return true
                }
            }
            if (looksNetworkMessage(current.message)) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun looksNetworkMessage(message: String?): Boolean {
        val text = message?.lowercase().orEmpty()
        if (text.isBlank()) return false
        return NETWORK_MESSAGE_FRAGMENTS.any { it in text }
    }

    // Media3 PlaybackException IO/timeout codes (1.4.x). Kept as literals so a BOM bump cannot
    // break compile if a constant is renamed.
    private val NETWORK_ERROR_CODES = intArrayOf(
        2000, // ERROR_CODE_IO_UNSPECIFIED
        2001, // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        2002, // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        2003, // ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
        2004, // ERROR_CODE_IO_BAD_HTTP_STATUS
        1003, // ERROR_CODE_TIMEOUT
    )

    private val NETWORK_MESSAGE_FRAGMENTS = listOf(
        "timeout",
        "timed out",
        "unknownhost",
        "unable to resolve",
        "connection refused",
        "connection reset",
        "network is unreachable",
        "software caused connection abort",
        "failed to connect",
        "econnrefused",
        "econnreset",
        "enotfound",
        "unexpected end of stream",
        "end of file",
        "eof",
        "broken pipe",
        "host is down",
        "no route to host",
        "http 502",
        "http 503",
        "http 504",
        "502 bad gateway",
        "503 service",
        "504 gateway",
    )
}
