package com.ambient.tv.data.local

import com.ambient.tv.BuildConfig

/**
 * Account keys compiled into a private build (build.sh PRIVATE_KEYS, private-keys.env). Empty in
 * public builds. A built-in key always wins and its input is hidden in Settings.
 */
object BuiltInKeys {
    val spotifyClientId: String? get() = BuildConfig.BUILTIN_SPOTIFY_CLIENT_ID.trim().takeIf(String::isNotEmpty)
    val tmdbApiKey: String? get() = BuildConfig.BUILTIN_TMDB_API_KEY.trim().takeIf(String::isNotEmpty)
    val fanartApiKey: String? get() = BuildConfig.BUILTIN_FANART_API_KEY.trim().takeIf(String::isNotEmpty)
    val openSubtitlesApiKey: String? get() = BuildConfig.BUILTIN_OPENSUBTITLES_API_KEY.trim().takeIf(String::isNotEmpty)

    /** Both Telegram application values, only when both are valid. */
    val monogram: Pair<Int, String>? get() {
        val id = BuildConfig.BUILTIN_MONOGRAM_API_ID.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val hash = BuildConfig.BUILTIN_MONOGRAM_API_HASH.trim().takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) } ?: return null
        return id to hash.lowercase(java.util.Locale.ROOT)
    }
}
