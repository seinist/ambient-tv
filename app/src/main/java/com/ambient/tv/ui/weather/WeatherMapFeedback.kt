package com.ambient.tv.ui.weather

import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherMapError
import com.ambient.tv.notifications.FeedbackDelivery
import com.ambient.tv.notifications.FeedbackPhase
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.OperationFeedback

internal fun weatherMapFeedback(error: WeatherMapError, request: WeatherMapRequest, manual: Boolean = false): OperationFeedback {
    val phase = when (error) {
        WeatherMapError.NETWORK_DISABLED, WeatherMapError.NO_COVERAGE -> FeedbackPhase.INFO
        WeatherMapError.RATE_LIMITED -> FeedbackPhase.WARNING
        else -> FeedbackPhase.ERROR
    }
    return OperationFeedback(
        key = "${request.scope}:refresh", scope = request.scope,
        message = when (error) {
            WeatherMapError.NETWORK_DISABLED -> R.string.weather_maps_network_disabled
            WeatherMapError.OFFLINE -> R.string.weather_maps_offline
            WeatherMapError.UNAVAILABLE -> R.string.weather_maps_unavailable
            WeatherMapError.RATE_LIMITED -> R.string.weather_maps_rate_limited
            WeatherMapError.ACCESS_DENIED -> R.string.weather_maps_access_denied
            WeatherMapError.NO_COVERAGE -> R.string.weather_maps_no_coverage
        },
        phase = phase,
        subject = listOfNotNull(request.provider?.title, request.location.name).joinToString(" · "),
        kind = NoticeKind.CONNECTION,
        delivery = if (manual && phase != FeedbackPhase.INFO) FeedbackDelivery.ACTION else FeedbackDelivery.INLINE,
    )
}

internal fun weatherMapLayerFeedback(request: WeatherMapRequest, coverage: Boolean): OperationFeedback = OperationFeedback(
    key = "${request.scope}:${if (coverage) "coverage" else "base"}", scope = request.scope,
    message = if (coverage) R.string.weather_maps_coverage_missing else R.string.weather_maps_base_missing,
    phase = FeedbackPhase.WARNING, delivery = FeedbackDelivery.INLINE,
)
