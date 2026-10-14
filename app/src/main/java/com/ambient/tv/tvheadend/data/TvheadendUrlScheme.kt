package com.ambient.tv.tvheadend.data

/**
 * Resolves the HTTP scheme for TVHeadend API/stream URLs.
 *
 * Prefer an explicit `https://` (or `http://`) prefix in the configured host/URL.
 * Otherwise honour the optional [useHttps] settings toggle (default off → HTTP),
 * matching classic LAN TVH which speaks cleartext on port 9981.
 */
object TvheadendUrlScheme {
    fun resolve(configuredHost: String, useHttps: Boolean): String {
        val trimmed = configuredHost.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https"
            trimmed.startsWith("http://", ignoreCase = true) -> "http"
            useHttps -> "https"
            else -> "http"
        }
    }
}
