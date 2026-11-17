package com.ambient.tv.ui.navigation

import com.ambient.tv.connectivity.HostHealth
import com.ambient.tv.data.local.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Reconcile even when a login completed before the sidebar began observing changes. */
internal fun <T> observeSidebarAvailability(
    vararg changes: Flow<*>,
    snapshot: () -> T,
): Flow<T> = merge(*changes)
    .map { snapshot() }
    .onStart { emit(snapshot()) }
    .distinctUntilChanged()

/**
 * Offline means a confirmed outage for the configured source's host. A stored response, unknown
 * reachability, or an answering service that rejects access is not evidence of an offline host.
 * Source ids stay specific (files:<id>/dlna:<id>); one NAS cannot mark every Files entry offline.
 */
internal fun sidebarOfflineSources(
    configuredSources: Collection<String>,
    healthOf: (String) -> HostHealth,
): Set<String> = configuredSources.filterTo(linkedSetOf()) { healthOf(it) == HostHealth.OFFLINE }


/** Preference subset whose changes alter the contents of provider pages in the sidebar. */
internal data class SidebarSubmenuSnapshot(
    val youtubeFeedKeys: List<String>,
    val youtubeStartFeedKey: String,
    val spotifySubmenuKeys: List<String>,
    val radioSubmenuKeys: List<String>,
    val tvheadendSubmenuKeys: List<String>,
)

internal fun AppPreferences.sidebarSubmenuSnapshot() = SidebarSubmenuSnapshot(
    youtubeFeedKeys = youtubeFeedKeys,
    youtubeStartFeedKey = youtubeStartFeedKey,
    spotifySubmenuKeys = spotifySubmenuKeys,
    radioSubmenuKeys = radioSubmenuKeys,
    tvheadendSubmenuKeys = tvheadendSubmenuKeys,
)
