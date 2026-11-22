package com.ambient.tv.ui.home

/** Connection status controls programme claims and launcher entries, never saved channel rows. */
internal fun homeTvSourceReachable(sourceId: String, offlineSystems: Set<String>, tvheadendOnline: Boolean): Boolean {
    val system = sourceId.substringBefore(':')
    val source = if (system == "tvheadend") system else sourceId
    return source !in offlineSystems && (system != "tvheadend" || tvheadendOnline)
}

/** Publish an empty Android TV launcher channel only after all enabled sources are known down. */
internal fun allHomeTvSourcesOffline(
    systems: List<String>,
    offlineSystems: Set<String>,
    tvheadendFailed: Boolean,
): Boolean = systems.isNotEmpty() && systems.all { system ->
    system in offlineSystems || (system == "tvheadend" && tvheadendFailed)
}
