package com.ambient.tv.ui.navigation

/** One source of truth for the first usable content destination after setup and on cold start. */
internal fun configuredContentRoute(
    plex: Boolean,
    jellyfin: Boolean,
    emby: Boolean,
    tvheadend: Boolean,
    youtube: Boolean,
    spotify: Boolean,
    radio: Boolean,
    files: Boolean,
    trailers: Boolean,
    iptvSourceId: String?,
    telegram: Boolean = false,
): String? = when {
    plex || jellyfin || emby || tvheadend -> Destinations.HOME
    youtube -> Destinations.YOUTUBE
    telegram -> Destinations.TELEGRAM
    spotify -> Destinations.SPOTIFY
    radio -> Destinations.RADIO
    files -> Destinations.FILES
    trailers -> Destinations.TRAILERS
    !iptvSourceId.isNullOrBlank() -> Destinations.liveTvSection("live", iptvSourceId)
    else -> null
}
