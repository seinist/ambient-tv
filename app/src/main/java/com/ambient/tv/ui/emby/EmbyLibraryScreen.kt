package com.ambient.tv.ui.emby

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ambient.tv.ui.home.HomeScreen
import com.ambient.tv.ui.home.HomeViewModel

@Composable
fun EmbyLibraryScreen(
    onItemClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppHome: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    HomeScreen(
        focusStateKey = "emby:home",
        onOpenSettings = onOpenSettings,
        onOpenAppHome = onOpenAppHome,
        onBack = onBack,
        onItemClick = onItemClick,
        embyOnly = true,
        viewModel = viewModel,
    )
}
