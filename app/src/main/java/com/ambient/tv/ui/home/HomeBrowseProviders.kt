package com.ambient.tv.ui.home

/** The offline notice and recovery checks must describe the sources on this page. */
internal fun homeBrowseProviders(
    plexConfigured: Boolean,
    jellyfinConfigured: Boolean,
    embyConfigured: Boolean,
    plexOnly: Boolean,
    jellyfinOnly: Boolean,
    embyOnly: Boolean,
): List<String> = buildList {
    if (plexConfigured && !jellyfinOnly && !embyOnly) add("plex")
    if (jellyfinConfigured && !plexOnly && !embyOnly) add("jellyfin")
    if (embyConfigured && !plexOnly && !jellyfinOnly) add("emby")
}
