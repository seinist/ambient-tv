package com.ambient.tv.ui.navigation

import androidx.navigation.NavController

/**
 * Starts a YouTube player so that Back and the real end of the queue return to the page the
 * queue came from (channel, feed, Home), with focus on the last played video.
 *
 * - A details page of the same browse queue ([closeDetails]) closes together with the player:
 *   after Next the player may show a completely different video than that details page.
 * - A channel page opened from a player's OSD sits above that old player. The new player
 *   replaces the old one *below* the channel, so the stack becomes `… → Channel → Player`.
 *   Navigation cannot remove a middle entry, so the channel entry is popped with its saved
 *   state and restored: the same entry (same ViewModel with its loaded pages, scroll state
 *   and browse session) comes back, only the old player below it is gone.
 *
 * Without a player below the channel and without a details page this is a plain navigate.
 */
internal fun NavController.navigateToYouTubeBrowsePlayer(route: String, closeDetails: Boolean) {
    if (closeDetails && currentDestination?.route == Destinations.YOUTUBE_DETAIL) popBackStack()
    val channel = currentBackStackEntry
    if (channel?.destination?.route == Destinations.YOUTUBE_CHANNEL &&
        previousBackStackEntry?.destination?.route == Destinations.YOUTUBE_PLAYER
    ) {
        val args = channel.arguments
        val channelRoute = Destinations.youtubeChannel(
            args?.getString("channelId").orEmpty(),
            args?.getString("title"),
            args?.getString("avatar"),
        )
        if (popBackStack(channel.destination.id, inclusive = true, saveState = true)) {
            navigate(channelRoute) {
                popUpTo(Destinations.YOUTUBE_PLAYER) { inclusive = true }
                restoreState = true
            }
        }
    }
    navigate(route)
}
