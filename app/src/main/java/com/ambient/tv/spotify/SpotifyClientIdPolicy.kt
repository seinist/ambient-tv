package com.ambient.tv.spotify

/** The app ships without a Spotify client ID; only the ID entered in settings is used. */
internal object SpotifyClientIdPolicy {
    fun effective(stored: String?): String = stored.orEmpty().trim()
    fun configured(stored: String?): Boolean = effective(stored).isNotEmpty()
}
