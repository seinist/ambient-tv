package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.games.gameSystemVector

internal const val ROMM_MENU_ID = "romm"

/**
 * RomM's entries for the TV sidebar and the phone's Sources menu: like Games (All, Favorites,
 * Recent), then RomM's platforms by the names given on the server, with their console's icon.
 */
@Composable
internal fun rommMenuDestinations(state: SidebarUiState): List<MenuDestination> {
    if (!state.rommEnabled) return emptyList()
    return listOf(
        MenuDestination("romm:all", stringResource(R.string.game_library_all), Icons.Filled.Apps, Destinations.ROMM),
        MenuDestination("romm:favorites", stringResource(R.string.radio_favorites), Icons.Filled.Favorite,
            Destinations.rommSection("favorites")),
        MenuDestination("romm:recent", stringResource(R.string.radio_recent), Icons.Filled.History,
            Destinations.rommSection("recent")),
    ) + state.rommPlatforms.map { platform ->
        MenuDestination("romm:${platform.section}", platform.title, gameSystemVector(platform.systemId),
            Destinations.rommSection(platform.section))
    }
}
