package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.navigation.Destinations

/**
 * SmartTube categories switch inside the live YouTube screen instead of through navigation.
 *
 * Every feed is also a navigation route (deep links, Home rows, launcher). Selecting one in the
 * sidebar or the header chips used to navigate: the old destination left the stack, a new screen
 * and view model were created, the header and focus tree rebuilt and the rows came back only
 * after the new model had read the content cache. With the screen already on top, the sidebar
 * now sends the feed key here; the screen applies it to its existing view model and keeps the
 * header, the sidebar highlight follows [displayed].
 */
internal object YouTubeFeedSelection : com.ambient.tv.ui.common.InPlaceSectionSelection()

/** The feed key a sidebar/chip route addresses: the root route is the configured start feed. */
internal fun youtubeFeedKeyOfRoute(route: String, startFeedKey: String): String? =
    com.ambient.tv.ui.common.sectionKeyOfRoute(route, Destinations.YOUTUBE, startFeedKey, "youtube_feed/")

/** Whether the YouTube feed screen is the current destination, so a feed switch can stay in place. */
internal fun youtubeFeedSwitchesInPlace(currentRoute: String?, displayedFeedKey: String?, targetFeedKey: String?): Boolean =
    com.ambient.tv.ui.common.sectionSwitchesInPlace(currentRoute,
        setOf(Destinations.YOUTUBE, Destinations.YOUTUBE_FEED), displayedFeedKey, targetFeedKey)
