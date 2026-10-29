package com.ambient.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AppStartNavigationTest {
    private fun route(
        plex: Boolean = false,
        jellyfin: Boolean = false,
        emby: Boolean = false,
        tvheadend: Boolean = false,
        youtube: Boolean = false,
        spotify: Boolean = false,
        radio: Boolean = false,
        files: Boolean = false,
        trailers: Boolean = false,
        iptv: String? = null,
    ) = configuredContentRoute(plex, jellyfin, emby, tvheadend, youtube, spotify, radio, files, trailers, iptv)

    @Test fun everyStandaloneSourceHasAUsableStartDestination() {
        assertEquals(Destinations.HOME, route(plex = true))
        assertEquals(Destinations.HOME, route(jellyfin = true))
        assertEquals(Destinations.HOME, route(emby = true))
        assertEquals(Destinations.HOME, route(tvheadend = true))
        assertEquals(Destinations.YOUTUBE, route(youtube = true))
        assertEquals(Destinations.SPOTIFY, route(spotify = true))
        assertEquals(Destinations.RADIO, route(radio = true))
        assertEquals(Destinations.FILES, route(files = true))
        assertEquals(Destinations.TRAILERS, route(trailers = true))
        assertEquals(Destinations.liveTvSection("live", "iptv:one"), route(iptv = "iptv:one"))
        assertNull(route())
    }

    @Test fun mediaHomeKeepsPriorityWhenSeveralSourcesExist() {
        assertEquals(Destinations.HOME, route(plex = true, youtube = true, files = true, iptv = "iptv:one"))
    }
}
