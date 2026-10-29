package com.ambient.tv.ui.navigation

import android.net.Uri

object Destinations {
    const val SETUP_COMPLETE = "setup_complete/{destination}"
    fun setupComplete(destination: String) = "setup_complete/${Uri.encode(destination)}"
    const val INITIAL_SETUP = "initial_setup"
    const val SETUP_SETTINGS = "setup_settings/{category}"
    const val SERVER_SELECT = "server_select"
    const val FILES = "files_browse"
    const val FILE_SOURCE = "files_source/{sourceId}"
    const val PHOTO_VIEWER = "photo_viewer"
    const val FILE_FAVORITES = "files_favorites"
    const val FILE_RECENT = "files_recent"
    const val FILE_CONTINUE = "files_continue"
    const val DLNA = "dlna"
    const val DLNA_SERVER = "dlna/{serverId}"
    const val HOME = "home"
    const val PLEX = "plex"
    const val JELLYFIN = "jellyfin"
    const val EMBY = "emby"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_SECTION = "live_tv/{tab}?sourceId={sourceId}"
    const val YOUTUBE = "youtube"
    const val TRAILERS = "trailers"
    const val TRAILER_FEED = "trailers/feed/{feedKey}"
    const val ITEM_TRAILERS = "trailers/item/{ratingKey}"
    fun itemTrailers(ratingKey: String) = "trailers/item/${Uri.encode(ratingKey)}"
    const val TMDB_TRAILERS = "trailers/tmdb/{mediaType}/{tmdbId}"
    fun tmdbTrailers(mediaType: String, tmdbId: Int) = "trailers/tmdb/${Uri.encode(mediaType)}/$tmdbId"
    const val YOUTUBE_FEED = "youtube_feed/{feedKey}"
    const val SPOTIFY = "spotify"
    const val RADIO = "radio"
    const val RADIO_SECTION = "radio_section/{sectionKey}"
    const val RADIO_PLAYER = "radio_player"
    const val SPOTIFY_SECTION = "spotify_section/{sectionKey}"
    const val SPOTIFY_DETAIL = "spotify_detail/{type}/{id}"
    const val SPOTIFY_PLAYER = "spotify_player?uri={uri}"
    const val YOUTUBE_DETAIL = "youtube_detail/{videoId}"
    const val YOUTUBE_CHANNEL = "youtube_channel/{channelId}?title={title}&avatar={avatar}"
    const val YOUTUBE_PLAYER = "youtube_player/{videoId}?trailer={trailer}"
    const val TVH_PLAYER = "tvh_player/{kind}/{id}?sourceId={sourceId}"
    const val DETAIL = "detail/{ratingKey}?collectionId={collectionId}&collectionSort={collectionSort}"
    const val PLAYER = "player/{ratingKey}?mediaIndex={mediaIndex}&restart={restart}&collectionId={collectionId}&collectionSort={collectionSort}&detailOrigin={detailOrigin}"
    const val SECTION = "section/{sectionKey}"
    const val FILTERED_BROWSE = "browse/{kind}/{filterId}/{title}"
    const val DETAIL_FILTERED_BROWSE = "detail_browse/{kind}/{filterId}/{title}"

    /** NavDestination route patterns, not the concrete URLs passed to navigate(). */
    fun isDetailRoute(route: String?): Boolean =
        route == DETAIL || route == YOUTUBE_DETAIL || route == SPOTIFY_DETAIL

    fun setupSettings(category: String) = "setup_settings/${Uri.encode(category)}"
    fun detail(ratingKey: String, collectionId: String? = null, collectionSort: String? = null) = buildString {
        append("detail/").append(Uri.encode(ratingKey))
        collectionId?.let {
            append("?collectionId=").append(it)
            append("&collectionSort=").append(Uri.encode(collectionSort.orEmpty()))
        }
    }
    fun fileSource(sourceId: String) = "files_source/${Uri.encode(sourceId)}"
    fun dlnaServer(serverId: String) = "dlna/${Uri.encode(serverId)}"
    fun youtubeFeed(feedKey: String) = "youtube_feed/${Uri.encode(feedKey)}"
    /** SmartTube's root route shows the chosen start section; every other section has its feed route. */
    fun youtubeSection(feedKey: String, startFeedKey: String) = if (feedKey == startFeedKey) YOUTUBE else youtubeFeed(feedKey)
    fun trailerFeed(feedKey: String) = "trailers/feed/${Uri.encode(feedKey)}"
    fun spotifySection(sectionKey: String) = "spotify_section/${Uri.encode(sectionKey)}"
    fun radioSection(sectionKey: String) =
        if (sectionKey == "popular") RADIO else "radio_section/${Uri.encode(sectionKey)}"
    fun youtubeDetail(videoId: String) = "youtube_detail/${Uri.encode(videoId)}"
    fun youtubeChannel(channelId: String, title: String? = null, avatarUrl: String? = null) =
        "youtube_channel/${Uri.encode(channelId)}?title=${Uri.encode(title.orEmpty())}&avatar=${Uri.encode(avatarUrl.orEmpty())}"
    fun spotifyDetail(type: String, id: String) = "spotify_detail/${Uri.encode(type)}/${Uri.encode(id)}"
    fun spotifyPlayer(uri: String? = null) = if (uri.isNullOrBlank()) "spotify_player" else "spotify_player?uri=${Uri.encode(uri)}"
    fun youtubePlayer(videoId: String) = "youtube_player/${Uri.encode(videoId)}"
    fun trailerPlayer(videoId: String) = "${youtubePlayer(videoId)}?trailer=true"
    fun liveTvSection(tab: String, sourceId: String? = null) = "live_tv/${Uri.encode(tab)}" + sourceQuery(sourceId)
    fun tvhPlayer(kind: String, id: String, sourceId: String? = null) = "tvh_player/$kind/${Uri.encode(id)}" + sourceQuery(sourceId)
    private fun sourceQuery(id: String?) = id?.takeIf { it.isNotBlank() }?.let { "?sourceId=${Uri.encode(it)}" }.orEmpty()
    fun player(
        ratingKey: String,
        mediaIndex: Int = 0,
        restart: Boolean = false,
        collectionId: String? = null,
        collectionSort: String? = null,
        /** Set when an episode was started from a detail page, so leaving the player can open that episode. */
        detailOrigin: Boolean = false,
    ) = buildString {
        append("player/").append(Uri.encode(ratingKey))
        append("?mediaIndex=").append(mediaIndex)
        append("&restart=").append(restart)
        collectionId?.let {
            append("&collectionId=").append(it)
            append("&collectionSort=").append(Uri.encode(collectionSort.orEmpty()))
        }
        if (detailOrigin) append("&detailOrigin=true")
    }
    fun section(sectionKey: String) = "section/${Uri.encode(sectionKey)}"
    fun filteredBrowse(kind: String, filterId: String, title: String) = "browse/$kind/${Uri.encode(filterId)}/${Uri.encode(title)}"
    fun detailFilteredBrowse(kind: String, filterId: String, title: String) =
        "detail_browse/$kind/${Uri.encode(filterId)}/${Uri.encode(title)}"
}
