package com.ambient.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.data.weather.WeatherLocation
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherSettings
import com.ambient.tv.ui.common.FeedbackLayout
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.OperationFeedbackScope
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvSettingRow
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.leftKeyAction
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens

@Composable
fun WeatherSettingsScreen(
    onBack: (() -> Unit)? = null,
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: WeatherViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val search by viewModel.search.collectAsState()
    val networkAllowed by viewModel.networkAllowed.collectAsState()
    val contentVisible = LocalWeatherPresentation.current.contentVisible
    val locale = LocalConfiguration.current.locales[0]
    val language = locale?.language ?: "en"
    val tokens = LocalUiTokens.current
    val chain = rememberPageFocusChain()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    OperationFeedbackScope(viewModel.feedbackReporter, WEATHER_SEARCH_SCOPE, enabled = contentVisible)
    if (!embedded) {
        BackHandler(enabled = onBack != null) { onBack?.invoke() }
        ReportSidebarContentReady(true)
    }
    val contentModifier = if (embedded) modifier else modifier.fillMaxSize().padding(
        start = tokens.screenPadding, end = tokens.screenPadding, bottom = tokens.screenPadding,
        top = pageHeaderTop(tokens.screenPadding))

    Column(contentModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!embedded) PageHeader(focusChain = chain, actions = {
            onBack?.let { back ->
                TvChip(stringResource(R.string.back), icon = Icons.AutoMirrored.Filled.ArrowBack,
                    iconOnly = true, modifier = Modifier.leftKeyAction(back), onClick = back)
            }
        }) {
            Text(stringResource(R.string.weather_settings), style = MaterialTheme.typography.headlineMedium)
        }
        TvLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)
            .then(if (embedded) Modifier else Modifier.pageContentUp(chain)),
            contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            allowUpExit = !embedded, wrapVerticalFocus = false) {
            item(key = "enabled") {
                TvSettingRow(stringResource(R.string.weather_enable), stringResource(R.string.weather_enable_hint),
                    checked = settings.enabled, onCheckedChange = viewModel::setEnabled)
            }
            if (!networkAllowed) item(key = "local-only") {
                OperationFeedbackContent(weatherForecastFeedback(WeatherError.LOCAL_ONLY, cached = false),
                    modifier = Modifier.fillMaxWidth(), layout = FeedbackLayout.COMPACT)
            }
            item(key = "places-title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.weather_manage_locations), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.weather_default_location_hint), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            items(settings.locations, key = { "saved:${it.id}" }) { location ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionButton(locationLabel(location), icon = Icons.Filled.LocationOn,
                        selected = settings.selectedLocation?.id == location.id,
                        modifier = Modifier.weight(1f), fillWidth = true,
                        onClick = { viewModel.selectLocation(location.id) })
                    TvChip(stringResource(R.string.weather_remove_location, location.name),
                        icon = Icons.Filled.DeleteOutline, iconOnly = true,
                        textMaxLines = 2, modifier = Modifier.widthIn(max = 180.dp),
                        onClick = { viewModel.removeLocation(location.id) })
                }
            }
            if (settings.locations.size >= WeatherSettings.MAX_LOCATIONS) item(key = "places-limit") {
                Text(stringResource(R.string.weather_locations_limit, WeatherSettings.MAX_LOCATIONS),
                    style = MaterialTheme.typography.bodySmall, color = muted)
            } else if (settings.enabled && networkAllowed) {
                item(key = "location-search") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvTextField(search.query, onValueChange = { viewModel.searchLocations(it, language) },
                            label = stringResource(R.string.weather_search_location), modifier = Modifier.fillMaxWidth(),
                            requireExplicitEditing = false)
                        if (search.query.isNotBlank()) TvChip(stringResource(R.string.weather_clear_search),
                            icon = Icons.Filled.Close, onClick = viewModel::clearSearch)
                    }
                }
                if (search.loading || search.failed || search.completed && search.results.isEmpty()) item(key = "search-status") {
                    if (search.failed) OperationFeedbackContent(weatherSearchFeedback(search.error),
                        modifier = Modifier.fillMaxWidth(), layout = FeedbackLayout.FIELD,
                        actionLabel = stringResource(R.string.weather_retry),
                        actionLoading = search.loading, onAction = { viewModel.retrySearch(language) })
                    else Text(stringResource(if (search.loading) R.string.weather_searching else R.string.weather_search_empty),
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
                items(search.results, key = { "result:${it.id}" }) { location ->
                    TvActionButton(locationLabel(location), icon = Icons.Filled.LocationOn, fillWidth = true,
                        onClick = { viewModel.addLocation(location) })
                }
                item(key = "geocoding-source") {
                    WeatherSourceLink(stringResource(R.string.weather_location_source), "https://www.geonames.org/")
                }
            } else if (!settings.enabled) item(key = "enable-before-search") {
                Text(stringResource(R.string.weather_enable_to_search), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            item(key = "display-title") {
                Text(stringResource(R.string.weather_display), style = MaterialTheme.typography.titleLarge)
            }
            item(key = "sidebar-display") {
                TvSettingRow(stringResource(R.string.weather_show_sidebar), stringResource(R.string.weather_show_sidebar_hint),
                    checked = settings.showInSidebar, onCheckedChange = { value -> viewModel.changeSettings { it.copy(showInSidebar = value) } })
            }
            item(key = "header-display") {
                TvSettingRow(stringResource(R.string.weather_show_header), stringResource(R.string.weather_show_header_hint),
                    checked = settings.showInHeader, onCheckedChange = { value -> viewModel.changeSettings { it.copy(showInHeader = value) } })
            }
            item(key = "screensaver-display") {
                TvSettingRow(stringResource(R.string.weather_show_screensaver), stringResource(R.string.weather_show_screensaver_hint),
                    checked = settings.showInScreensaver, onCheckedChange = { value -> viewModel.changeSettings { it.copy(showInScreensaver = value) } })
            }
            item(key = "units-title") {
                Text(stringResource(R.string.weather_units), style = MaterialTheme.typography.titleLarge)
            }
            item(key = "temperature-unit") {
                val options = listOf("celsius" to "°C", "fahrenheit" to "°F")
                PlayerDropUpSelector(label = stringResource(R.string.weather_temperature),
                    value = options.firstOrNull { it.first == settings.temperatureUnit }?.second ?: "°C",
                    options = options, selectedKey = settings.temperatureUnit, onSelected = viewModel::setTemperatureUnit)
            }
            item(key = "wind-unit") {
                val options = listOf("kmh" to "km/h", "mph" to "mph", "ms" to "m/s", "kn" to "kn")
                PlayerDropUpSelector(label = stringResource(R.string.weather_wind),
                    value = weatherWindUnit(settings.windUnit), options = options, selectedKey = settings.windUnit,
                    onSelected = viewModel::setWindUnit)
            }
            item(key = "map-providers-title") {
                Text(stringResource(R.string.weather_map_providers), style = MaterialTheme.typography.titleLarge)
            }
            item(key = "radar-provider") {
                val options = listOf("" to stringResource(R.string.weather_provider_none)) +
                    WeatherMapProvider.forKind(WeatherMapKind.RADAR).map { it.id to it.title }
                PlayerDropUpSelector(label = stringResource(R.string.weather_radar_provider),
                    value = options.firstOrNull { it.first == settings.radarProvider }?.second ?: options.first().second,
                    options = options, selectedKey = settings.radarProvider,
                    onSelected = { provider -> viewModel.changeSettings { it.copy(radarProvider = provider) } })
            }
            item(key = "satellite-provider") {
                val options = listOf("" to stringResource(R.string.weather_provider_none)) +
                    WeatherMapProvider.forKind(WeatherMapKind.SATELLITE).map { it.id to it.title }
                PlayerDropUpSelector(label = stringResource(R.string.weather_satellite_provider),
                    value = options.firstOrNull { it.first == settings.satelliteProvider }?.second ?: options.first().second,
                    options = options, selectedKey = settings.satelliteProvider,
                    onSelected = { provider -> viewModel.changeSettings { it.copy(satelliteProvider = provider) } })
            }
            item(key = "map-settings-hint") {
                Text(stringResource(R.string.weather_maps_manual_hint), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            item(key = "source") { WeatherAttribution() }
        }
    }
}

private fun locationLabel(location: WeatherLocation): String =
    listOf(location.name, location.region, location.country).filter { it.isNotBlank() }.distinct().joinToString(" · ")
