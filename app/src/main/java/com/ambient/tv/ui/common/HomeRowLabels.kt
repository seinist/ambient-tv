package com.ambient.tv.ui.common

import android.content.Context
import com.ambient.tv.R
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.data.local.genre
import com.ambient.tv.data.local.homeRowType

/**
 * Resolve defaults at display time so custom Home layouts follow the current UI language and the
 * name the user gave the row's system.
 */
fun HomeRowDefinition.displayTitle(context: Context): String {
    if (title.isNotBlank()) return title
    if (system == HomeRowDefinition.SYSTEM_TRAILERS) {
        val feed = com.ambient.tv.trailers.TrailerFeed.entries.firstOrNull { it.name == contentKey }
        val name = MediaSystem.TRAILERS.customName ?: context.getString(R.string.trailers_title)
        return name + " · " + context.getString(feed?.label ?: R.string.trailers_cinema)
    }
    rankedRowTitle(context)?.let { row ->
        val mediaSystem = MediaSystem.fromKey(system)
        val name = mediaSystem?.customName ?: mediaSystem?.let { context.getString(it.label) } ?: system
        return context.getString(R.string.settings_home_source_system_row, name, row)
    }
    if (contentKey in setOf("live", "recent_channels") && system != HomeRowDefinition.SYSTEM_TVHEADEND) {
        val name = MediaSystem.fromKey(system)?.let { it.customName ?: context.getString(it.label) } ?: system
        val label = context.getString(if (contentKey == "live") R.string.tvh_live else R.string.settings_home_source_tvh_recent).replace("TVHeadend", "").trim()
        return "$name · $label"
    }
    val resource = when (system to contentKey) {
        HomeRowDefinition.SYSTEM_ALL to "continue" -> R.string.settings_home_source_all_continue
        HomeRowDefinition.SYSTEM_PLEX to "continue" -> R.string.settings_home_source_plex_continue
        HomeRowDefinition.SYSTEM_PLEX to "recent" -> R.string.settings_home_source_plex_recent
        HomeRowDefinition.SYSTEM_PLEX to "collections" -> R.string.settings_home_source_plex_collections
        HomeRowDefinition.SYSTEM_PLEX to "libraries" -> R.string.settings_home_source_plex_libraries
        HomeRowDefinition.SYSTEM_JELLYFIN to "continue" -> R.string.settings_home_source_jellyfin_continue
        HomeRowDefinition.SYSTEM_JELLYFIN to "recent" -> R.string.settings_home_source_jellyfin_recent
        HomeRowDefinition.SYSTEM_JELLYFIN to "collections" -> R.string.settings_home_source_jellyfin_collections
        HomeRowDefinition.SYSTEM_JELLYFIN to "libraries" -> R.string.settings_home_source_jellyfin_libraries
        HomeRowDefinition.SYSTEM_EMBY to "continue" -> R.string.settings_home_source_emby_continue
        HomeRowDefinition.SYSTEM_EMBY to "recent" -> R.string.settings_home_source_emby_recent
        HomeRowDefinition.SYSTEM_EMBY to "collections" -> R.string.settings_home_source_emby_collections
        HomeRowDefinition.SYSTEM_EMBY to "libraries" -> R.string.settings_home_source_emby_libraries
        HomeRowDefinition.SYSTEM_TVHEADEND to "recent_channels" -> R.string.settings_home_source_tvh_recent
        HomeRowDefinition.SYSTEM_TVHEADEND to "live" -> R.string.settings_home_source_tvh_live
        HomeRowDefinition.SYSTEM_TVHEADEND to "guide" -> R.string.settings_home_source_tvh_guide
        HomeRowDefinition.SYSTEM_TVHEADEND to "recordings" -> R.string.settings_home_source_tvh_recordings
        HomeRowDefinition.SYSTEM_TVHEADEND to "timers" -> R.string.settings_home_source_tvh_timers
        HomeRowDefinition.SYSTEM_YOUTUBE to "home" -> R.string.settings_home_source_youtube_home
        HomeRowDefinition.SYSTEM_YOUTUBE to "subscriptions" -> R.string.settings_home_source_youtube_subscriptions
        HomeRowDefinition.SYSTEM_YOUTUBE to "history" -> R.string.settings_home_source_youtube_history
        HomeRowDefinition.SYSTEM_YOUTUBE to "playlists" -> R.string.settings_home_source_youtube_playlists
        HomeRowDefinition.SYSTEM_YOUTUBE to "shorts" -> R.string.nav_youtube_shorts
        HomeRowDefinition.SYSTEM_YOUTUBE to "music" -> R.string.nav_youtube_music
        HomeRowDefinition.SYSTEM_YOUTUBE to "news" -> R.string.nav_youtube_news
        HomeRowDefinition.SYSTEM_YOUTUBE to "gaming" -> R.string.nav_youtube_gaming
        HomeRowDefinition.SYSTEM_YOUTUBE to "live" -> R.string.nav_youtube_live
        HomeRowDefinition.SYSTEM_YOUTUBE to "kids" -> R.string.nav_youtube_kids
        HomeRowDefinition.SYSTEM_SPOTIFY to "home" -> R.string.settings_home_source_spotify_home
        HomeRowDefinition.SYSTEM_SPOTIFY to "liked" -> R.string.settings_home_source_spotify_liked
        HomeRowDefinition.SYSTEM_SPOTIFY to "playlists" -> R.string.settings_home_source_spotify_playlists
        HomeRowDefinition.SYSTEM_SPOTIFY to "albums" -> R.string.settings_home_source_spotify_albums
        HomeRowDefinition.SYSTEM_SPOTIFY to "artists" -> R.string.settings_home_source_spotify_artists
        HomeRowDefinition.SYSTEM_SPOTIFY to "now_playing" -> R.string.settings_home_source_spotify_now_playing
        HomeRowDefinition.SYSTEM_RADIO to "home" -> R.string.radio_title
        HomeRowDefinition.SYSTEM_PHOTOS to "recent" -> R.string.photos_recent_row
        else -> return contentKey.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    val title = context.getString(resource)
    return MediaSystem.fromKey(system)?.renamed(title) ?: title
}

/** How a server-ranked row is headed, without its system; null for every other row. */
fun HomeRowDefinition.rankedRowTitle(context: Context): String? = when (homeRowType(contentKey)) {
    HomeRowDefinition.CONTENT_WATCHED -> context.getString(R.string.home_recently_watched)
    HomeRowDefinition.CONTENT_TOP_UNWATCHED -> context.getString(R.string.home_top_unwatched)
    HomeRowDefinition.CONTENT_TOP_GENRE -> genre?.let { context.getString(R.string.home_top_genre, it) }
    HomeRowDefinition.CONTENT_BECAUSE_WATCHED -> context.getString(R.string.home_because_watched_row)
    HomeRowDefinition.CONTENT_HUBS -> context.getString(R.string.home_server_hubs)
    else -> null
}

/** Name of a kind of row on a media server's own start page. */
fun systemHomeRowTypeTitle(context: Context, type: String): String = context.getString(
    when (type) {
        "continue" -> R.string.home_continue_watching
        "recent" -> R.string.home_recently_added
        "collections" -> R.string.settings_system_home_collections
        "libraries" -> R.string.settings_system_home_libraries
        HomeRowDefinition.CONTENT_WATCHED -> R.string.home_recently_watched
        HomeRowDefinition.CONTENT_TOP_UNWATCHED -> R.string.home_top_unwatched
        HomeRowDefinition.CONTENT_TOP_GENRE -> R.string.settings_system_home_genres
        HomeRowDefinition.CONTENT_BECAUSE_WATCHED -> R.string.home_because_watched_row
        else -> R.string.home_server_hubs
    },
)
