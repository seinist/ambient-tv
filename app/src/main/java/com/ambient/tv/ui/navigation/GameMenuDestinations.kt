package com.ambient.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.games.GameSystems
import com.ambient.tv.ui.games.gameSystemVector

/** One Games inventory for the TV sidebar and the phone's Sources menu. */
@Composable
internal fun gamesMenuDestinations(state: SidebarUiState): List<MenuDestination> {
    if (!state.gamesAvailable) return emptyList()
    return listOf(
        MenuDestination("games:all", stringResource(R.string.game_library_all),
            Icons.Filled.Apps, Destinations.GAME_LIBRARY),
        MenuDestination("games:favorites", stringResource(R.string.game_library_favorites),
            Icons.Filled.Favorite, Destinations.gameLibrarySection("favorites")),
        MenuDestination("games:recent", stringResource(R.string.game_library_recent),
            Icons.Filled.History, Destinations.gameLibrarySection("recent")),
    ) + GameSystems.sortIdsByName(state.gameSystemIds).map { id ->
        val system = requireNotNull(GameSystems.byId(id))
        MenuDestination("games:$id", system.shortName, gameSystemVector(id),
            Destinations.gameLibrarySection(id))
    }
}
