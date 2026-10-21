package com.ambient.tv.ui.navigation

import androidx.navigation.NavController

/**
 * Opens the app home screen from the handheld Startseite control.
 *
 * [NavController.navigate] back to the graph start with `saveState` and `restoreState` can put
 * the page that is already showing back on top, so the tap looks like it did nothing.
 * [NavController.popBackStack] then reports success without changing that page. Replacing the
 * whole graph with Home is the one path that always lands on the start screen.
 */
internal fun NavController.openAppHome() {
    if (currentDestination?.route == Destinations.HOME) return
    navigate(Destinations.HOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
