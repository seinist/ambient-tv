package com.ambient.tv.ui.weather

import com.ambient.tv.R
import com.ambient.tv.data.weather.WeatherError
import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.FeedbackDelivery
import com.ambient.tv.notifications.FeedbackPhase
import com.ambient.tv.notifications.OperationFeedbackReporter
import org.junit.Assert.*
import org.junit.Test

class WeatherFeedbackTest {
    @Test fun backgroundRefreshAndSavedDataNeverSendGlobalNotifications() {
        for (error in WeatherError.entries) {
            assertFalse(weatherForecastFeedback(error, cached = true).shouldNotify(false))
            assertFalse(weatherSearchFeedback(error).shouldNotify(false))
        }
        val saved = weatherForecastFeedback(null, cached = true, delivery = FeedbackDelivery.ACTION)
        assertEquals(FeedbackPhase.WARNING, saved.phase)
        assertEquals(R.string.weather_stale, saved.message)
        assertNull(saved.detail)
        assertFalse(saved.shouldNotify(false))
    }

    @Test fun disabledAndLocalOnlyAreInformationalEvenAfterAManualRetry() {
        for (error in listOf(WeatherError.DISABLED, WeatherError.LOCAL_ONLY)) {
            val forecast = weatherForecastFeedback(error, cached = false, delivery = FeedbackDelivery.ACTION)
            val search = weatherSearchFeedback(error, delivery = FeedbackDelivery.ACTION)
            assertEquals(FeedbackPhase.INFO, forecast.phase)
            assertEquals(FeedbackPhase.INFO, search.phase)
            assertFalse(forecast.shouldNotify(false))
            assertFalse(search.shouldNotify(false))
        }
    }

    @Test fun explicitRetryReportsOnceOnlyAfterTheRelevantViewIsHidden() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        val owner = Any()
        val failure = weatherForecastFeedback(WeatherError.UNAVAILABLE, cached = true,
            subject = "Berlin", delivery = FeedbackDelivery.ACTION)
        reporter.visibility(owner, WEATHER_FORECAST_SCOPE)
        reporter.complete(reporter.begin(failure.key), failure)
        assertTrue(notices.isEmpty())
        val ticket = reporter.begin(failure.key)
        reporter.visibility(owner, null)
        reporter.complete(ticket, failure)
        assertNull(reporter.complete(ticket, failure))
        assertEquals(1, notices.size)
        assertEquals("Berlin", notices.single().subject)
        assertFalse(notices.single().interruptPlayback)
        assertEquals(R.string.weather_stale, failure.detail)
    }

    @Test fun searchAndForecastDoNotSuppressEachOthersHiddenActionFailure() {
        val notices = mutableListOf<AppNotice>()
        val reporter = OperationFeedbackReporter { notices.add(it) }
        reporter.visibility(Any(), WEATHER_SEARCH_SCOPE)
        val search = weatherSearchFeedback(WeatherError.RATE_LIMITED, delivery = FeedbackDelivery.ACTION)
        reporter.complete(reporter.begin(search.key), search)
        assertEquals(FeedbackPhase.WARNING, search.phase)
        assertTrue(notices.isEmpty())
        val forecast = weatherForecastFeedback(WeatherError.OFFLINE, cached = false, delivery = FeedbackDelivery.ACTION)
        reporter.complete(reporter.begin(forecast.key), forecast)
        assertEquals(1, notices.size)
        assertEquals(WEATHER_FORECAST_SCOPE, notices.single().key)
    }
}
