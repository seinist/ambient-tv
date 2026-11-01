package com.ambient.tv.ui.navigation

import androidx.navigation.NavController

/**
 * Starts a Telegram player so that Back, Stop and the real end of the queue return to the page
 * the queue came from (channel, section, Home), like [navigateToYouTubeBrowsePlayer]:
 *
 * - The post details page the player was started from is replaced by the player; after Next
 *   the player may show a different post than that details page.
 * - A channel page opened from a player's OSD sits above that old player. The new player
 *   replaces the old one below it (the channel entry is popped with its saved state and
 *   restored), so the stack becomes `… → Channel → Player`.
 */
internal fun NavController.navigateToTelegramPlayer(route: String) {
    if (currentDestination?.route == Destinations.TELEGRAM_DETAIL) popBackStack()
    val page = currentBackStackEntry
    val pageRoute = page?.destination?.route
    if ((pageRoute == Destinations.TELEGRAM_SECTION || pageRoute == Destinations.TELEGRAM) &&
        previousBackStackEntry?.destination?.route == Destinations.TELEGRAM_PLAYER
    ) {
        val restored = if (pageRoute == Destinations.TELEGRAM) Destinations.TELEGRAM
            else Destinations.telegramSection(page.arguments?.getString("sectionKey") ?: "recent")
        if (popBackStack(page.destination.id, inclusive = true, saveState = true)) {
            navigate(restored) {
                popUpTo(Destinations.TELEGRAM_PLAYER) { inclusive = true }
                restoreState = true
            }
        }
    }
    navigate(route)
}
