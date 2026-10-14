package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScalePolicyTest {
    @Test fun startupDefaultsFollowDisplayResolution() {
        for ((size, expected) in listOf(
            (1280 to 720) to 1f, (1920 to 1080) to 1f,
            (2560 to 1440) to .9f, (3840 to 2160) to .7f,
            (7680 to 4320) to .7f, (0 to 0) to 1f,
            (3840 to 1080) to 1f,
        )) {
            assertEquals(expected, UiScalePolicy.initialScale(true, size.first, size.second), 0f)
            assertEquals(expected, UiScalePolicy.initialScale(true, size.second, size.first), 0f)
            assertEquals(1f, UiScalePolicy.initialScale(false, size.first, size.second), 0f)
        }
    }

    @Test fun initializationAndManualValuesSurviveRestart() {
        val storage = memoryPreferences()
        val preferences = AppPreferences(storage)
        preferences.initializeUiScale(true, 3840, 2160)
        assertEquals(.7f, preferences.uiScale, 0f)
        AppPreferences(storage).initializeUiScale(true, 1920, 1080)
        assertEquals(.7f, preferences.uiScale, 0f)
        for (scale in listOf(.5f, 1f, 1.5f)) {
            preferences.uiScale = scale
            val restarted = AppPreferences(storage)
            restarted.initializeUiScale(true, 3840, 2160)
            assertEquals(scale, restarted.uiScale, 0f)
        }
    }

    @Test fun storageAndRendererShareBoundsAndRejectNonFiniteValues() {
        val preferences = AppPreferences(memoryPreferences())
        for ((input, expected) in listOf(.1f to .5f, 2f to 1.5f, Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f)) {
            preferences.uiScale = input
            assertEquals(expected, preferences.uiScale, 0f)
            assertEquals(expected, UiScalePolicy.normalize(input), 0f)
        }
    }
}
