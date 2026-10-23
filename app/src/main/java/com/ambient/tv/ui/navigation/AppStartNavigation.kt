package com.ambient.tv.ui.navigation

/** One source of truth for the first usable content destination after setup and on cold start. */
internal fun configuredContentRoute(
    plex: Boolean,
    jellyfin: Boolean,
    emby: Boolean,
    tvheadend: Boolean,
    youtube: Boolean,
    spotify: Boolean,
    files: Boolean,
    trailers: Boolean,
    iptvSourceId: String?,
): String? = when {
    plex || jellyfin || emby || tvheadend -> Destinations.HOME
    youtube -> Destinations.YOUTUBE
    spotify -> Destinations.SPOTIFY
    files -> Destinations.FILES
    trailers -> Destinations.TRAILERS
    !iptvSourceId.isNullOrBlank() -> Destinations.liveTvSection("live", iptvSourceId)
    else -> null
}
