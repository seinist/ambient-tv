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
