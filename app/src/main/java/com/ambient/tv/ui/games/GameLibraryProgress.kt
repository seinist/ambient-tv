package com.ambient.tv.ui.games

import android.content.Context
import com.ambient.tv.R

/** The library and name-database page describe the same active operation. */
internal fun gameLibraryProgressMessage(context: Context, state: GameLibraryUiState): String = when (state.scanPhase) {
    GameLibraryScanPhase.NAME_DATABASES -> if (state.scanDiscovered > 0)
        context.getString(R.string.game_name_databases_progress, state.scanProcessed, state.scanDiscovered)
        else context.getString(R.string.game_name_databases_loading)
    GameLibraryScanPhase.MATCHING_NAMES -> if (state.scanDiscovered > 0)
        context.getString(R.string.game_name_databases_matching_progress, state.scanProcessed, state.scanDiscovered)
        else context.getString(R.string.game_name_databases_matching)
    GameLibraryScanPhase.METADATA -> if (state.scanDiscovered > 0)
        context.getString(R.string.game_folder_metadata_progress, state.scanProcessed, state.scanDiscovered)
        else context.getString(R.string.game_folder_enrich)
    GameLibraryScanPhase.FILES -> if (state.scanDiscovered > 0)
        context.getString(R.string.game_library_scan_progress, state.scanProcessed, state.scanDiscovered)
        else context.getString(R.string.game_library_scanning)
}
