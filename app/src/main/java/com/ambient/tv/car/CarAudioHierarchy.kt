package com.ambient.tv.car

internal data class CarAudioHierarchy(
    val roots: List<String>,
    val radioFolders: List<String>,
    val liveFolders: List<String>,
    val libraryFolders: List<String>,
)

/**
 * Car hosts default to four browsable root tabs. Live TV stays independent from Radio
 * Browser; a lightweight library container keeps music and storage reachable when full.
 * https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy
 */
internal fun carAudioHierarchy(
    radioEnabled: Boolean,
    iptvAvailable: Boolean,
    tvheadendAvailable: Boolean,
    spotifyAvailable: Boolean,
    storageAvailable: Boolean,
): CarAudioHierarchy {
    val liveFolders = buildList {
        if (iptvAvailable) add(CarAudioIds.LIVE_RADIO_IPTV)
        if (tvheadendAvailable) add(CarAudioIds.LIVE_RADIO_TVH)
    }
    val sourceTabs = buildList {
        if (radioEnabled) add(CarAudioIds.RADIO_ROOT)
        if (liveFolders.isNotEmpty()) add(CarAudioIds.LIVE_ROOT)
        if (spotifyAvailable) add(CarAudioIds.SPOTIFY_ROOT)
    }
    val combineLibraries = storageAvailable && sourceTabs.size + 2 > 4
    val libraryTabs = when {
        combineLibraries -> listOf(CarAudioIds.LIBRARY_ROOT)
        storageAvailable -> listOf(CarAudioIds.MUSIC_LIBRARIES, CarAudioIds.STORAGE_ROOT)
        else -> listOf(CarAudioIds.MUSIC_LIBRARIES)
    }
    return CarAudioHierarchy(
        roots = sourceTabs + libraryTabs,
        radioFolders = if (radioEnabled) listOf(
            CarAudioIds.RADIO_FAVORITES, CarAudioIds.RADIO_RECENT, CarAudioIds.RADIO_POPULAR,
            CarAudioIds.RADIO_ALL, CarAudioIds.RADIO_GENRES, CarAudioIds.RADIO_COUNTRIES, CarAudioIds.RADIO_LANGUAGES,
        ) else emptyList(),
        liveFolders = liveFolders,
        libraryFolders = listOf(CarAudioIds.MUSIC_LIBRARIES) +
            if (storageAvailable) listOf(CarAudioIds.STORAGE_ROOT) else emptyList(),
    )
}
