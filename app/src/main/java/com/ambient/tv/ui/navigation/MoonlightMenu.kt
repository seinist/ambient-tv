package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R

internal const val MOONLIGHT_MENU_ID = "moonlight"

/** Visible only when at least one Sunshine host is configured in the explicit native build. */
@Composable
internal fun moonlightMenuDestinations(state: SidebarUiState): List<MenuDestination> {
    if (!state.moonlightEnabled) return emptyList()
    return listOf(
        MenuDestination("moonlight:all", stringResource(R.string.game_library_all),
            Icons.Filled.Apps, Destinations.MOONLIGHT),
        MenuDestination("moonlight:favorites", stringResource(R.string.game_library_favorites),
            Icons.Filled.Favorite, Destinations.moonlightSection("favorites")),
        MenuDestination("moonlight:recent", stringResource(R.string.game_library_recent),
            Icons.Filled.History, Destinations.moonlightSection("recent")),
    ) + state.moonlightHosts.map { host ->
        MenuDestination("moonlight:host:" + host.id,
            host.name.ifBlank { host.host } + if (host.id in state.moonlightPairedHosts) "" else " · " + stringResource(R.string.moonlight_not_paired),
            Icons.Filled.Computer,
            Destinations.moonlightSection("host:" + host.id))
    }
}
