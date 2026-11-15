package com.ambient.tv.ui.catalog

import com.ambient.tv.ui.settings.accentStyleKey
import com.ambient.tv.ui.settings.accentMotionKey

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.ui.settings.SettingsScreen
import com.ambient.tv.ui.settings.SettingsViewModel
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.performance.detectLowEndDevice
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
            val context = LocalContext.current
            val lowEnd = remember(context) { detectLowEndDevice(context) }
            val performance = remember(state.uiPerformanceModeKey, state.playbackPerformanceModeKey, lowEnd) {
                PerformanceProfile.resolve(PerformanceMode.fromKey(state.uiPerformanceModeKey), lowEnd,
                    PerformanceMode.fromKey(state.playbackPerformanceModeKey))
            }
            CompositionLocalProvider(LocalPerformanceProfile provides performance) {
                AmbientTvTheme(
                    uiScale = state.uiScale, accentColorKey = state.accentColorKey,
                    accentStyleKey = state.accentStyleKey,
                    accentMotionKey = state.accentMotionKey,
                    customAccentHex = state.customAccentHex, customPlaybackAccentHex = state.customPlaybackAccentHex,
                    complementaryPlayback = state.dualAccentEnabled,
                    themeModeKey = state.themeModeKey.substringBefore("~"),
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
}
