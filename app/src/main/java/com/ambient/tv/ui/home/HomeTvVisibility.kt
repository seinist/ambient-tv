package com.ambient.tv.ui.home

/**
 * Home shows currently available live-TV services, not the channel browser's offline cache.
 * The source ID carries the configured system before its profile/scope suffix.
 *
 * Other services can be present independently even when TVHeadend is offline.
 */
internal fun homeTvSourceVisible(
    sourceId: String,
    offlineSystems: Set<String>,
    tvheadendOnline: Boolean,
): Boolean {
    val system = sourceId.substringBefore(':')
    return system !in offlineSystems && (system != "tvheadend" || tvheadendOnline)
}

/** Publish an empty Android TV launcher channel only after all enabled sources are known down. */
internal fun allHomeTvSourcesOffline(
    systems: List<String>,
    offlineSystems: Set<String>,
    tvheadendFailed: Boolean,
): Boolean = systems.isNotEmpty() && systems.all { system ->
    system in offlineSystems || (system == "tvheadend" && tvheadendFailed)
}
