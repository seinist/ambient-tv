package com.ambient.tv.ui.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.settings.SettingsScreen
import com.ambient.tv.ui.settings.SettingsViewModel
import com.ambient.tv.ui.theme.AmbientTvTheme
import dagger.hilt.android.AndroidEntryPoint

/** Direct debug entry into the real appearance settings, including Save/Discard and persistence. */
@AndroidEntryPoint
class AppearanceSettingsActivity : ComponentActivity() {
    internal val settingsViewModel: SettingsViewModel by viewModels()

    override fun attachBaseContext(base: android.content.Context) {
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(java.util.Locale.GERMAN)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by settingsViewModel.uiState.collectAsState()
            AmbientTvTheme(
                uiScale = state.uiScale, accentColorKey = state.accentColorKey,
                customAccentHex = state.customAccentHex, customPlaybackAccentHex = state.customPlaybackAccentHex,
                themeModeKey = state.themeModeKey,
            ) {
                CompositionLocalProvider(
                    androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsScreen(onBack = ::finish, onDone = ::finish,
                            initialCategoryKey = "appearance", viewModel = settingsViewModel)
                    }
                }
            }
        }
    }
}
