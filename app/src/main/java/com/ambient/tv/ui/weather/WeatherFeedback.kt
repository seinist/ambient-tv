package com.ambient.tv.ui.weather

import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.notifications.FeedbackDelivery
import com.ambient.tv.notifications.FeedbackPhase
import com.ambient.tv.notifications.OperationFeedback

internal const val WEATHER_FORECAST_SCOPE = "weather:forecast"
internal const val WEATHER_SEARCH_SCOPE = "weather:search"

/** Presentation data for the shared feedback component; automatic failures always stay inline. */
internal fun weatherForecastFeedback(
    error: WeatherError?,
    cached: Boolean,
    subject: String? = null,
    delivery: FeedbackDelivery = FeedbackDelivery.INLINE,
): OperationFeedback = OperationFeedback(
    key = WEATHER_FORECAST_SCOPE,
    scope = WEATHER_FORECAST_SCOPE,
    message = when (error) {
        WeatherError.DISABLED -> R.string.weather_disabled
        WeatherError.LOCAL_ONLY -> R.string.weather_local_only
        WeatherError.OFFLINE -> R.string.weather_offline
        WeatherError.RATE_LIMITED -> R.string.weather_rate_limited
        WeatherError.UNAVAILABLE, WeatherError.INVALID_DATA -> R.string.weather_unavailable
        null -> R.string.weather_stale
    },
    phase = weatherFeedbackPhase(error),
    detail = when {
        error == null -> null
        cached -> R.string.weather_stale
        error == WeatherError.DISABLED -> R.string.weather_disabled_hint
        error == WeatherError.LOCAL_ONLY -> R.string.weather_local_only_hint
        else -> R.string.weather_unavailable_hint
    },
    subject = subject,
    delivery = if (error == null || error == WeatherError.DISABLED || error == WeatherError.LOCAL_ONLY)
        FeedbackDelivery.INLINE else delivery,
)

internal fun weatherSearchFeedback(
    error: WeatherError?,
    subject: String? = null,
    delivery: FeedbackDelivery = FeedbackDelivery.INLINE,
): OperationFeedback = OperationFeedback(
    key = WEATHER_SEARCH_SCOPE,
    scope = WEATHER_SEARCH_SCOPE,
    message = when (error) {
        WeatherError.DISABLED -> R.string.weather_disabled
        WeatherError.LOCAL_ONLY -> R.string.weather_local_only
        WeatherError.RATE_LIMITED -> R.string.weather_rate_limited
        else -> R.string.weather_search_failed
    },
    phase = if (error == null) FeedbackPhase.ERROR else weatherFeedbackPhase(error),
    subject = subject,
    delivery = if (error == WeatherError.DISABLED || error == WeatherError.LOCAL_ONLY)
        FeedbackDelivery.INLINE else delivery,
)

private fun weatherFeedbackPhase(error: WeatherError?): FeedbackPhase = when (error) {
    WeatherError.DISABLED, WeatherError.LOCAL_ONLY -> FeedbackPhase.INFO
    WeatherError.RATE_LIMITED, null -> FeedbackPhase.WARNING
    else -> FeedbackPhase.ERROR
}
