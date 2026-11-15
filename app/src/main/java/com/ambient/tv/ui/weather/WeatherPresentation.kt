package com.ambient.tv.ui.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.data.weather.WEATHER_CACHE_TTL_MILLIS
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay

/** The header and screensaver use the same cached forecast as the full weather page. */
data class WeatherPresentation(
    val header: WeatherSnapshot? = null,
    val screensaver: WeatherSnapshot? = null,
    /** NavHost stays composed below screensaver/standby/PiP; its pages must suspend their work. */
    val contentVisible: Boolean = true,
)

val LocalWeatherPresentation = staticCompositionLocalOf { WeatherPresentation() }

@HiltViewModel
class WeatherPresentationViewModel @Inject constructor(val repository: WeatherRepository) : ViewModel()

/** No background job: only a visible, enabled display refreshes while the activity is resumed. */
@Composable
fun rememberWeatherPresentation(
    headerVisible: Boolean,
    screensaverVisible: Boolean,
    contentVisible: Boolean = true,
    viewModel: WeatherPresentationViewModel = hiltViewModel(),
): WeatherPresentation {
    val repository = viewModel.repository
    val settings by repository.settings.collectAsStateWithLifecycle()
    val state by repository.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = settings.enabled && settings.selectedLocation != null &&
        ((headerVisible && settings.showInHeader) || (screensaverVisible && settings.showInScreensaver))
    LaunchedEffect(repository, lifecycle, active, settings.selectedLocation, settings.temperatureUnit, settings.windUnit) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                repository.refresh()
                delay(WEATHER_CACHE_TTL_MILLIS)
            }
        }
    }
    val data = state.data?.takeIf { settings.enabled && it.matches(settings) }
    return WeatherPresentation(
        header = data.takeIf { settings.showInHeader },
        screensaver = data.takeIf { settings.showInScreensaver },
        contentVisible = contentVisible,
    )
}
