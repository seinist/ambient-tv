package com.ambient.tv.ui.jellyfin

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.ui.home.HomeScreen
import com.ambient.tv.ui.home.HomeViewModel

@Composable
fun JellyfinLibraryScreen(
    onItemClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppHome: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    onOpenRelevantSettings: (String) -> Unit = { onOpenSettings() },
) {
    HomeScreen(
        focusStateKey = "jellyfin:home",
        onOpenSettings = onOpenSettings,
        onOpenRelevantSettings = onOpenRelevantSettings,
        onOpenAppHome = onOpenAppHome,
        onBack = onBack,
        onItemClick = onItemClick,
        jellyfinOnly = true,
        viewModel = viewModel,
    )
}
