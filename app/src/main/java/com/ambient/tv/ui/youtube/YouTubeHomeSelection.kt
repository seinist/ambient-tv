package com.ambient.tv.ui.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Only an actual Home chip/menu selection requests a fresh SmartTube Home feed.
 * A lifecycle resume (including coming back from playback) is not a selection.
 */
internal object YouTubeHomeSelection {
    private val changes = MutableStateFlow(0L)
    val requests: StateFlow<Long> = changes.asStateFlow()

    fun request() {
        changes.update { it + 1L }
    }
}

/** Closing or shortening an actual search restores Home's snapshot without a network call. */
internal fun youtubeRestoreHomeAfterSearch(previous: String, next: String): Boolean =
    previous.trim().length >= 2 && next.trim().length < 2

internal fun youtubeShouldRefreshOnEntry(
    feed: YouTubeFeed,
    firstEntry: Boolean,
    channelPage: Boolean,
    explicitHomeSelection: Boolean,
): Boolean = firstEntry || channelPage || feed != YouTubeFeed.HOME || explicitHomeSelection
