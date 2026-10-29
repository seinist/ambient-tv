package com.ambient.tv.data.local

import java.util.UUID
import kotlinx.serialization.Serializable

/** A user-defined Home row. `system` and `contentKey` deliberately stay stable string keys so
 * a backup remains useful when a provider is temporarily unavailable. */
@Serializable
data class HomeRowDefinition(
    val id: String = UUID.randomUUID().toString(),
    val system: String,
    val contentKey: String,
    val title: String = "",
    val displayMode: String = DISPLAY_POSTER,
    val enabled: Boolean = true,
) {
    companion object {
        const val SYSTEM_PLEX = "plex"
        const val SYSTEM_JELLYFIN = "jellyfin"
        const val SYSTEM_EMBY = "emby"
        const val SYSTEM_TVHEADEND = "tvheadend"
        const val SYSTEM_YOUTUBE = "youtube"
        const val SYSTEM_TRAILERS = "trailers"
        const val SYSTEM_SPOTIFY = "spotify"
        const val SYSTEM_RADIO = "radio"
        /** Rows across every media server (Plex, Jellyfin, Emby) at once. */
        const val SYSTEM_ALL = "all"
        /** Photos of all servers (Plex/Jellyfin/Emby photo libraries). */
        const val SYSTEM_PHOTOS = "photos"

        const val DISPLAY_POSTER = "poster"
        const val DISPLAY_LANDSCAPE = "landscape"
        const val DISPLAY_LIST = "list"
        val DISPLAY_MODES = setOf(DISPLAY_POSTER, DISPLAY_LANDSCAPE, DISPLAY_LIST)

        /** Rows a media server (Plex, Jellyfin, Emby) ranks itself. */
        const val CONTENT_WATCHED = "watched"
        const val CONTENT_TOP_UNWATCHED = "top_unwatched"
        const val CONTENT_BECAUSE_WATCHED = "because_watched"
        /** One row per genre; the genre follows a colon, spelled as the server spells it. */
        const val CONTENT_TOP_GENRE = "top_genre"
        /** The "Recommended" rows Plex builds for each library. */
        const val CONTENT_HUBS = "hubs"

        fun topGenre(genre: String) = "$CONTENT_TOP_GENRE:$genre"
    }
}

/** The genre of a "top in genre" row; null for every other row. */
val HomeRowDefinition.genre: String?
    get() = contentKey.takeIf { it.startsWith("${HomeRowDefinition.CONTENT_TOP_GENRE}:") }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

/** "top_genre:Drama" -> "top_genre": the kind of row, whatever it is parameterised with. */
fun homeRowType(contentKey: String): String = contentKey.substringBefore(':')

/**
 * Rows a media server's own start page can show, in the order it shows them. That page is not
 * laid out by the Home editor; each kind is switched on or off per system instead.
 */
fun systemHomeRowTypes(system: String): List<String> = listOfNotNull(
    "continue",
    "recent",
    HomeRowDefinition.CONTENT_TOP_UNWATCHED,
    HomeRowDefinition.CONTENT_TOP_GENRE,
    HomeRowDefinition.CONTENT_BECAUSE_WATCHED,
    HomeRowDefinition.CONTENT_WATCHED,
    HomeRowDefinition.CONTENT_HUBS.takeIf { system == HomeRowDefinition.SYSTEM_PLEX },
    "collections",
    "libraries",
)

/** Live TV channel rows show channel logos, which only fit landscape cards. */
val HomeRowDefinition.isLiveTvChannelRow: Boolean
    get() = contentKey in setOf("live", "recent_channels")

/** Live TV channel cards stay landscape; every other landscape row becomes poster. */
fun HomeRowDefinition.withDefaultPosterCards(): HomeRowDefinition {
    val liveTvCard = isLiveTvChannelRow
    return if (!liveTvCard && displayMode == HomeRowDefinition.DISPLAY_LANDSCAPE) {
        copy(displayMode = HomeRowDefinition.DISPLAY_POSTER)
    } else {
        this
    }
}

fun List<HomeRowDefinition>.withDefaultPosterCards(): List<HomeRowDefinition> =
    map { it.withDefaultPosterCards() }

/** True when a Home row's system is active and can actually serve content or a shortcut. */
fun homeSystemReady(
    system: String,
    plexReady: Boolean,
    jellyfinReady: Boolean,
    tvheadendReady: Boolean,
    youtubeReady: Boolean,
    spotifyReady: Boolean,
    radioReady: Boolean = false,
    embyReady: Boolean = false,
    photosReady: Boolean = false,
    trailersReady: Boolean = false,
): Boolean = when (system) {
    HomeRowDefinition.SYSTEM_ALL -> plexReady || jellyfinReady || embyReady
    HomeRowDefinition.SYSTEM_PHOTOS -> photosReady
    HomeRowDefinition.SYSTEM_TRAILERS -> trailersReady
    HomeRowDefinition.SYSTEM_PLEX -> plexReady
    HomeRowDefinition.SYSTEM_JELLYFIN -> jellyfinReady
    HomeRowDefinition.SYSTEM_EMBY -> embyReady
    HomeRowDefinition.SYSTEM_TVHEADEND -> tvheadendReady
    HomeRowDefinition.SYSTEM_YOUTUBE -> youtubeReady
    HomeRowDefinition.SYSTEM_SPOTIFY -> spotifyReady
    HomeRowDefinition.SYSTEM_RADIO -> radioReady
    else -> false
}
