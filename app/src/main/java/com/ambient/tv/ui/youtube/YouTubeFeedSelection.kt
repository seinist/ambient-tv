package com.ambient.tv.ui.youtube

import com.ambient.tv.ui.navigation.Destinations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
internal object YouTubeFeedSelection {
    data class Request(val serial: Long, val feedKey: String)

    private val mutableRequests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = mutableRequests.asStateFlow()

    /** Feed shown by the live YouTube feed screen, with that screen's owner token. */
    private val mutableDisplayed = MutableStateFlow<Pair<Any, String>?>(null)
    val displayed: StateFlow<Pair<Any, String>?> = mutableDisplayed.asStateFlow()

    fun request(feedKey: String) {
        mutableRequests.update { Request((it?.serial ?: 0L) + 1L, feedKey) }
    }

    fun show(owner: Any, feedKey: String) {
        mutableDisplayed.value = owner to feedKey
    }

    /** Only the owner clears its own entry: a replacement screen may already have shown its feed. */
    fun hide(owner: Any) {
        mutableDisplayed.update { if (it?.first === owner) null else it }
    }
}

/** The feed key a sidebar/chip route addresses: the root route is the configured start feed. */
internal fun youtubeFeedKeyOfRoute(route: String, startFeedKey: String): String? = when {
    route == Destinations.YOUTUBE -> startFeedKey
    route.startsWith("youtube_feed/") -> java.net.URLDecoder.decode(route.removePrefix("youtube_feed/"), "UTF-8").takeIf { it.isNotBlank() }
    else -> null
}

/** Whether the YouTube feed screen is the current destination, so a feed switch can stay in place. */
internal fun youtubeFeedSwitchesInPlace(currentRoute: String?, displayedFeedKey: String?, targetFeedKey: String?): Boolean =
    displayedFeedKey != null && targetFeedKey != null && targetFeedKey != displayedFeedKey &&
        (currentRoute == Destinations.YOUTUBE || currentRoute == Destinations.YOUTUBE_FEED)
