package com.ambient.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.weather.WEATHER_CACHE_TTL_MILLIS
import com.ambient.tv.data.weather.WeatherDay
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.data.weather.WeatherMapKind
import com.ambient.tv.data.weather.WeatherMapProvider
import com.ambient.tv.data.weather.WeatherSnapshot
import com.ambient.tv.data.weather.WeatherState
import com.ambient.tv.notifications.FeedbackPhase
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.common.FeedbackLayout
import com.ambient.tv.ui.common.OperationFeedbackContent
import com.ambient.tv.ui.common.OperationFeedbackScope
import com.ambient.tv.ui.common.PageChipRow
import com.ambient.tv.ui.common.PageFocusChain
import com.ambient.tv.ui.common.PageFocusLevel
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.StatePanel
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.activateOnTvKey
import com.ambient.tv.ui.common.handheldSwipeTabs
import com.ambient.tv.ui.common.leftKeyAction
import com.ambient.tv.ui.common.noSurfaceBorder
import com.ambient.tv.ui.common.noSurfaceScale
import com.ambient.tv.ui.common.pageContentUp
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.common.pageFocusStage
import com.ambient.tv.ui.common.rememberPageFocusChain
import com.ambient.tv.ui.sound.rememberUiSoundAction
import com.ambient.tv.ui.common.rememberUse24Hour
import com.ambient.tv.ui.common.rememberWallClock
import com.ambient.tv.ui.common.transparentSurfaceColors
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.navigation.ReportSidebarContentReady
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.theme.appControlSurface
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private val WEATHER_TABS = listOf("overview", "radar", "satellite")

@Composable
fun WeatherScreen(
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onOpenPlayer: ((WeatherMapKind, String, Int, Long) -> Unit)? = null,
    viewModel: WeatherViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.state.collectAsState()
    val contentVisible = LocalWeatherPresentation.current.contentVisible
    val owner = LocalLifecycleOwner.current
    val tokens = LocalUiTokens.current
    val handheld = LocalHandheldUi.current
    val focusChain = rememberPageFocusChain()
    val locationFocus = remember { FocusRequester() }
    var tab by rememberSaveable { mutableStateOf("overview") }
    val location = settings.selectedLocation
    val snapshot = state.data?.takeIf { it.matches(settings) }
    var settledError by remember(settings.enabled, location, settings.temperatureUnit, settings.windUnit) {
        mutableStateOf<WeatherError?>(null)
    }
    LaunchedEffect(state.error, state.loading) {
        if (!state.loading) settledError = state.error
    }
    // Keep retry controls mounted until their result arrives; a loading transition must not drop TV focus.
    val displayedError = state.error ?: settledError.takeIf { state.loading }
    val tick by rememberWallClock()
    val nowMillis = remember(tick) { System.currentTimeMillis() }
    OperationFeedbackScope(viewModel.feedbackReporter, WEATHER_FORECAST_SCOPE,
        enabled = contentVisible && tab == "overview")

    // These controls are usable before a response arrives. Do not make sidebar focus wait on HTTP.
    ReportSidebarContentReady(true)
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    // Map tabs own their map requests; a forecast is only needed by this overview or its header.
    LaunchedEffect(viewModel, owner, settings.enabled, location, settings.temperatureUnit,
        settings.windUnit, contentVisible, tab) {
        if (settings.enabled && location != null && contentVisible && tab == "overview") {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    viewModel.refreshVisible()
                    delay(WEATHER_CACHE_TTL_MILLIS)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().handheldSwipeTabs(
        tabs = WEATHER_TABS,
        selected = tab,
        onSelect = { tab = it },
        enabled = contentVisible,
    ).padding(start = tokens.screenPadding, end = tokens.screenPadding,
        top = pageHeaderTop(tokens.screenPadding), bottom = tokens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeader(focusChain = focusChain, actions = {
            onBack?.let { back ->
                TvChip(stringResource(R.string.back), icon = Icons.AutoMirrored.Filled.ArrowBack,
                    iconOnly = true, modifier = Modifier.leftKeyAction(back), onClick = back)
            }
            if (tab == "overview") TvChip(stringResource(R.string.weather_refresh), icon = Icons.Filled.Refresh,
                iconOnly = true, enabled = settings.enabled && location != null,
                loading = state.loading, onClick = viewModel::retryForecast)
            TvChip(stringResource(R.string.weather_settings), icon = Icons.Filled.Settings,
                iconOnly = true, onClick = onOpenSettings)
        }) {
            Text(stringResource(R.string.weather_title), style = MaterialTheme.typography.headlineMedium)
        }
        PageChipRow(focusChain, selectedIndex = WEATHER_TABS.indexOf(tab).coerceAtLeast(0)) {
            item(key = "overview") {
                TvChip(stringResource(R.string.weather_overview), selected = tab == "overview",
                    modifier = if (tab == "overview") Modifier.focusRequester(focusChain.selectedChip) else Modifier) { tab = "overview" }
            }
            item(key = "radar") {
                TvChip(stringResource(R.string.weather_radar), selected = tab == "radar",
                    modifier = if (tab == "radar") Modifier.focusRequester(focusChain.selectedChip) else Modifier) { tab = "radar" }
            }
            item(key = "satellite") {
                TvChip(stringResource(R.string.weather_satellite), selected = tab == "satellite",
                    modifier = if (tab == "satellite") Modifier.focusRequester(focusChain.selectedChip) else Modifier) { tab = "satellite" }
            }
        }

        when {
            !settings.enabled -> StatePanel(stringResource(R.string.weather_disabled),
                body = stringResource(R.string.weather_disabled_hint), actionLabel = stringResource(R.string.weather_setup),
                onAction = onOpenSettings, phase = FeedbackPhase.INFO,
                modifier = Modifier.fillMaxWidth().weight(1f))
            location == null -> StatePanel(stringResource(R.string.weather_choose_location),
                body = stringResource(R.string.weather_choose_location_hint), actionLabel = stringResource(R.string.weather_manage_locations),
                onAction = onOpenSettings, phase = FeedbackPhase.INFO,
                modifier = Modifier.fillMaxWidth().weight(1f))
            else -> {
                if (settings.locations.size > 1) LazyRow(
                    modifier = Modifier.pageFocusStage(focusChain, PageFocusLevel.CONTEXT, locationFocus),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.locations, key = { it.id }) { place ->
                        TvChip(place.name, selected = place.id == location.id, onClick = { viewModel.selectLocation(place.id) })
                    }
                }
                when (tab) {
                    "radar", "satellite" -> WeatherMapPanel(
                        location = location,
                        kind = if (tab == "radar") WeatherMapKind.RADAR else WeatherMapKind.SATELLITE,
                        providerId = if (tab == "radar") settings.radarProvider else settings.satelliteProvider,
                        onOpenSettings = onOpenSettings,
                        onOpenPlayer = onOpenPlayer?.let { launch ->
                            { chosen: WeatherMapProvider, zoom: Int, epoch: Long ->
                                launch(chosen.kind, chosen.id, zoom, epoch)
                            }
                        },
                        networkAllowed = settings.enabled,
                        modifier = Modifier.fillMaxWidth().weight(1f).pageContentUp(focusChain),
                    )
                    else -> {
                        if (snapshot != null) {
                            WeatherOverview(snapshot, state.copy(error = displayedError), nowMillis, focusChain,
                                onRetry = viewModel::retryForecast, modifier = Modifier.fillMaxWidth().weight(1f))
                        } else if (displayedError != null) {
                            val feedback = weatherForecastFeedback(displayedError, cached = false, subject = location.name)
                            StatePanel(stringResource(feedback.message, *feedback.args.toTypedArray()),
                                body = feedback.detail?.let { stringResource(it) },
                                phase = feedback.phase,
                                actionLabel = stringResource(R.string.weather_retry),
                                actionLoading = state.loading,
                                onAction = { if (!state.loading) viewModel.retryForecast() },
                                modifier = Modifier.fillMaxWidth().weight(1f))
                        } else {
                            WeatherLoadingOverview(location.name, handheld, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherOverview(
    snapshot: WeatherSnapshot,
    state: WeatherState,
    nowMillis: Long,
    focusChain: PageFocusChain,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val use24Hour = rememberUse24Hour()
    val todayLabel = stringResource(R.string.weather_today)
    val tomorrowLabel = stringResource(R.string.weather_tomorrow)
    val localNow = Instant.ofEpochMilli(nowMillis).atZone(weatherZone(snapshot.timezone))
    val firstHour = localNow.truncatedTo(ChronoUnit.HOURS).toEpochSecond()
    val hours = remember(snapshot.hourly, firstHour) { snapshot.hourly.filter { it.timeEpochSeconds >= firstHour }.take(24) }
    val days = remember(snapshot.daily, localNow.toLocalDate()) {
        snapshot.daily.filter { it.date >= localNow.toLocalDate().toString() }.take(7)
    }
    var selectedDate by rememberSaveable(snapshot.location.id) { mutableStateOf<String?>(null) }
    var selectedHour by rememberSaveable(snapshot.location.id) { mutableStateOf<Long?>(null) }
    val selectedDay = days.firstOrNull { it.date == selectedDate } ?: days.firstOrNull()
    val hour = hours.firstOrNull { it.timeEpochSeconds == selectedHour } ?: hours.firstOrNull()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    TvLazyColumn(modifier.pageContentUp(focusChain).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp),
        allowUpExit = true, wrapVerticalFocus = false) {
        item(key = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (state.error != null || snapshot.isStale(nowMillis)) {
                    OperationFeedbackContent(
                        weatherForecastFeedback(state.error, cached = true, subject = snapshot.location.name),
                        modifier = Modifier.fillMaxWidth(), layout = FeedbackLayout.COMPACT,
                        actionLabel = stringResource(R.string.weather_retry),
                        onAction = onRetry,
                        actionLoading = state.loading, requestActionFocus = false,
                    )
                }
                Text(stringResource(R.string.weather_updated,
                    weatherTimestamp(snapshot.fetchedAtMillis, snapshot.timezone, locale, use24Hour)),
                    style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        item(key = "current") { CurrentWeather(snapshot, days.firstOrNull(), locale) }
        item(key = "hours") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.weather_next_hours), style = MaterialTheme.typography.titleLarge)
                if (hours.isEmpty()) OperationFeedbackContent(
                    weatherForecastFeedback(null, cached = true, subject = snapshot.location.name).copy(
                        message = R.string.weather_forecast_expired, detail = null, phase = FeedbackPhase.INFO),
                    modifier = Modifier.fillMaxWidth(), layout = FeedbackLayout.COMPACT,
                )
                else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(2.dp)) {
                        items(hours, key = { it.timeEpochSeconds }) { entry ->
                            WeatherForecastCard(
                                title = weatherTime(entry.timeEpochSeconds, snapshot.timezone, locale, use24Hour),
                                code = entry.weatherCode, isDay = entry.isDay,
                                primary = weatherTemperature(entry.temperature, snapshot.temperatureUnit, locale, false),
                                secondary = weatherMeasure(entry.precipitationProbability, "%", locale),
                                isSelected = hour?.timeEpochSeconds == entry.timeEpochSeconds,
                                onClick = { selectedHour = entry.timeEpochSeconds },
                                modifier = Modifier.width(if (LocalHandheldUi.current) 106.dp else 128.dp),
                            )
                        }
                    }
                    hour?.let { entry ->
                        Text(stringResource(R.string.weather_hour_detail,
                            weatherTime(entry.timeEpochSeconds, snapshot.timezone, locale, use24Hour),
                            stringResource(weatherConditionRes(entry.weatherCode)),
                            weatherMeasure(entry.precipitationProbability, "%", locale),
                            weatherMeasure(entry.precipitation, "mm", locale, 1)),
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
        item(key = "days") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.weather_seven_days), style = MaterialTheme.typography.titleLarge)
                if (days.isEmpty()) OperationFeedbackContent(
                    weatherForecastFeedback(null, cached = true, subject = snapshot.location.name).copy(
                        message = R.string.weather_forecast_expired, detail = null, phase = FeedbackPhase.INFO),
                    modifier = Modifier.fillMaxWidth(), layout = FeedbackLayout.COMPACT,
                )
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(2.dp)) {
                    items(days, key = { it.date }) { day ->
                        WeatherForecastCard(
                            title = weatherDayName(day.date, snapshot.timezone, locale, todayLabel, tomorrowLabel, nowMillis),
                            code = day.weatherCode,
                            primary = stringResource(R.string.weather_range,
                                weatherTemperature(day.temperatureMax, snapshot.temperatureUnit, locale, false),
                                weatherTemperature(day.temperatureMin, snapshot.temperatureUnit, locale, false)),
                            secondary = weatherMeasure(day.precipitationProbability, "%", locale),
                            isSelected = selectedDay?.date == day.date,
                            onClick = { selectedDate = day.date },
                            modifier = Modifier.width(if (LocalHandheldUi.current) 128.dp else 150.dp),
                        )
                    }
                }
            }
        }
        selectedDay?.let { day ->
            item(key = "day-details") {
                WeatherDayDetails(day, snapshot,
                    weatherDayName(day.date, snapshot.timezone, locale, todayLabel, tomorrowLabel, nowMillis), locale, use24Hour)
            }
        }
        item(key = "attribution") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.weather_local_times, snapshot.timezone),
                    style = MaterialTheme.typography.labelSmall, color = muted)
                WeatherAttribution()
            }
        }
    }
}

@Composable
private fun CurrentWeather(snapshot: WeatherSnapshot, today: WeatherDay?, locale: Locale) {
    val current = snapshot.current
    val handheld = LocalHandheldUi.current
    val metrics: @Composable () -> Unit = {
        WeatherMetric(stringResource(R.string.weather_high_low), stringResource(R.string.weather_range,
            weatherTemperature(today?.temperatureMax, snapshot.temperatureUnit, locale, false),
            weatherTemperature(today?.temperatureMin, snapshot.temperatureUnit, locale, false)))
        WeatherMetric(stringResource(R.string.weather_wind), weatherMeasure(current?.windSpeed, weatherWindUnit(snapshot.windUnit), locale))
        WeatherMetric(stringResource(R.string.weather_humidity), weatherMeasure(current?.humidity, "%", locale))
    }
    DeviceActionRow(Modifier.fillMaxWidth().heightIn(min = 172.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically, stack = handheld) {
        Column(if (handheld) Modifier.fillMaxWidth() else Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(snapshot.location.name, style = MaterialTheme.typography.headlineMedium)
            Text(listOf(snapshot.location.region, snapshot.location.country).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(weatherTemperature(current?.temperature, snapshot.temperatureUnit, locale),
                    style = MaterialTheme.typography.displayMedium)
                Icon(weatherIcon(current?.weatherCode, current?.isDay ?: true), null, Modifier.size(44.dp))
            }
            Text(stringResource(weatherConditionRes(current?.weatherCode)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.weather_feels_like, weatherTemperature(current?.apparentTemperature, snapshot.temperatureUnit, locale)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (handheld) DeviceActionRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) { metrics() }
        else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { metrics() }
    }
}

@Composable
private fun WeatherForecastCard(
    title: String,
    code: Int?,
    primary: String,
    secondary: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDay: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    val sounds = LocalUiSoundPlayer.current
    val activate = rememberUiSoundAction(onClick = onClick)
    val condition = stringResource(weatherConditionRes(code))
    Surface(onClick = activate, colors = transparentSurfaceColors(), scale = noSurfaceScale(), border = noSurfaceBorder(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        modifier = modifier.activateOnTvKey(onClick = activate)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) sounds?.playFocus() }
            .semantics { selected = isSelected }) {
        Column(Modifier.fillMaxWidth().appControlSurface(focused, selected = isSelected, shape = shape).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(weatherIcon(code, isDay), condition, Modifier.size(28.dp))
            Text(primary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeatherDayDetails(day: WeatherDay, snapshot: WeatherSnapshot, title: String, locale: Locale, use24Hour: Boolean) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .38f),
        RoundedCornerShape(LocalUiTokens.current.cardRadius)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$title · ${stringResource(weatherConditionRes(day.weatherCode))}", style = MaterialTheme.typography.titleMedium)
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            WeatherMetric(stringResource(R.string.weather_precipitation), weatherMeasure(day.precipitation, "mm", locale, 1))
            WeatherMetric(stringResource(R.string.weather_rain_probability), weatherMeasure(day.precipitationProbability, "%", locale))
            WeatherMetric(stringResource(R.string.weather_wind), weatherMeasure(day.windSpeedMax, weatherWindUnit(snapshot.windUnit), locale))
            WeatherMetric(stringResource(R.string.weather_gusts), weatherMeasure(day.windGustsMax, weatherWindUnit(snapshot.windUnit), locale))
        }
        DeviceActionRow(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            WeatherMetric(stringResource(R.string.weather_sunrise), weatherTime(day.sunriseEpochSeconds, snapshot.timezone, locale, use24Hour))
            WeatherMetric(stringResource(R.string.weather_sunset), weatherTime(day.sunsetEpochSeconds, snapshot.timezone, locale, use24Hour))
            WeatherMetric(stringResource(R.string.weather_uv_index), weatherNumber(day.uvIndexMax, locale, 1))
        }
    }
}

@Composable
private fun WeatherMetric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WeatherLoadingOverview(location: String, handheld: Boolean, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.weather_loading, location), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(if (handheld) 214.dp else 172.dp).background(fill, shape))
        Text(stringResource(R.string.weather_next_hours), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(if (handheld) 3 else 6) { Box(Modifier.weight(1f).height(130.dp).background(fill, shape)) }
        }
        Text(stringResource(R.string.weather_seven_days), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(if (handheld) 3 else 7) { Box(Modifier.weight(1f).height(130.dp).background(fill, shape)) }
        }
    }
}
