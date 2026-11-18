package com.ambient.tv.ui.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.data.weather.WEATHER_CACHE_TTL_MILLIS
import com.ambient.tv.data.weather.WeatherRepository
import com.ambient.tv.data.weather.WeatherSettings
import com.ambient.tv.data.weather.WeatherSnapshot
import com.ambient.tv.data.weather.WeatherState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay

/** The header and screensaver use the same cached forecast as the full weather page. */
data class WeatherPresentation(
    val header: WeatherSnapshot? = null,
    val screensaver: WeatherSnapshot? = null,
    /** NavHost stays composed below screensaver/standby/PiP; its pages must suspend their work. */
    val contentVisible: Boolean = true,
) {
    val hasHeader: Boolean get() = header != null
}

/**
 * A forecast may have only hourly/daily values (accepted for the full weather page).
 * Compact clock/weather views need an actual current temperature: without it they
 * fall back to the original standalone clock rather than drawing an empty widget.
 */
internal fun weatherPresentationFromState(
    settings: WeatherSettings,
    state: WeatherState,
    contentVisible: Boolean = true,
    nowMillis: Long = System.currentTimeMillis(),
): WeatherPresentation {
    val canShow = settings.enabled && settings.selectedLocation != null
    val usable = state.data?.takeIf {
        canShow && it.matches(settings) && it.current?.temperature?.isFinite() == true && !it.isStale(nowMillis)
    }
    val header = usable.takeIf { settings.showInHeader }
    return WeatherPresentation(
        header = header,
        screensaver = usable.takeIf { settings.showInScreensaver },
        contentVisible = contentVisible,
    )
}

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
    // Recompose once when the cached reading expires, without a repeating clock ticker.
    // The time sample MUST be refreshed when HTTP publishes new data: retaining the
    // pre-request timestamp can make a newer snapshot look as if it came from the future.
    var freshnessTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(active, state.data?.fetchedAtMillis) {
        val expiresAt = state.data?.fetchedAtMillis?.plus(WEATHER_CACHE_TTL_MILLIS) ?: return@LaunchedEffect
        if (active) {
            val remaining = expiresAt - System.currentTimeMillis()
            if (remaining > 0L) delay(remaining)
            freshnessTick++
        }
    }
    val nowMillis = remember(state, active, freshnessTick) { System.currentTimeMillis() }
    LaunchedEffect(repository, lifecycle, active, settings.selectedLocation, settings.temperatureUnit, settings.windUnit) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Show a still-fresh disk snapshot immediately, then replace it with live data.
                repository.refresh(force = true)
                delay(WEATHER_CACHE_TTL_MILLIS)
            }
        }
    }
    return weatherPresentationFromState(settings, state, contentVisible, nowMillis)
}
