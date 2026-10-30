package com.ambient.tv.ui.plex

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.ui.home.HomeScreen
import com.ambient.tv.ui.home.HomeViewModel

/** Plex-only landing page with the familiar home feeds, independent from the editable app Home. */
@Composable
fun PlexLibraryScreen(
    onItemClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRelevantSettings: (String) -> Unit = { onOpenSettings() },
    onOpenAppHome: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    HomeScreen(
        focusStateKey = "plex:home",
        onOpenSettings = onOpenSettings,
        onOpenRelevantSettings = onOpenRelevantSettings,
        onOpenAppHome = onOpenAppHome,
        onBack = onBack,
        onItemClick = onItemClick,
        plexOnly = true,
        viewModel = viewModel,
    )
}
