package com.ambient.tv.ui.weather

import com.ambient.tv.ui.FULLSCREEN_ROUTES
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.returnsFromFullscreenPlayer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherMapPlayerNavigationTest {
    @Test fun weatherPlayerIsFullscreenButWeatherBrowseHasItsSidebar() {
        assertTrue(Destinations.WEATHER_PLAYER in FULLSCREEN_ROUTES)
        assertFalse(Destinations.WEATHER in FULLSCREEN_ROUTES)
        assertTrue(returnsFromFullscreenPlayer(Destinations.WEATHER_PLAYER, Destinations.WEATHER))
        assertFalse(returnsFromFullscreenPlayer(Destinations.WEATHER, Destinations.WEATHER_PLAYER))
    }

    @Test fun weatherMapPlayerUsesTheSharedChromeAndWeatherOnlyPanels() {
        val source = java.io.File("src/main/java/com/ambient/tv/ui/weather/WeatherMapPlayerScreen.kt").readText()
        assertTrue(source.contains("ReferencePlayerOverlayBackground("))
        assertTrue(source.contains("ReferencePlayerHeader("))
        assertTrue(source.contains("ReferencePlayerClock("))
        assertTrue(source.contains("ReferencePlayerBottomContent("))
        assertTrue(source.contains("PlayerOverlayVisibility("))
        assertTrue(source.contains("PlayerControlBar("))
        assertTrue(source.contains("PlayerTechBadgeRow"))
        assertTrue(source.contains("TvPlayerSeekBar("))
        assertTrue(source.contains("RightPanelOverlay("))
        assertTrue(source.contains("RightSidePanel("))
        assertTrue(source.contains("PlayerControlsAutoHide("))
        assertFalse(source.contains("Slider("))
        assertFalse(source.contains("PlayerPanelVisibility("))
    }

    @Test fun weatherPlayerDoesNotExposeSharedVideoAudioConfigUI() {
        val source = java.io.File("src/main/java/com/ambient/tv/ui/weather/WeatherMapPlayerScreen.kt").readText()
        assertFalse(source.contains("UnifiedPlayerPanel"))
        assertFalse(source.contains("SettingsPanel"))
        assertFalse(source.contains("BackgroundPlaybackSession"))
        assertTrue(source.contains("useController = false"))
    }
}
