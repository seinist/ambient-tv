package com.ambient.tv.ui.player

import com.ambient.tv.ui.player.backend.mpvUsesNetworkCache
import org.junit.Assert.*
import org.junit.Test

class LiveBufferPolicyTest {
    @Test fun media3LiveThresholdsStayBelowTheTimeshiftLead() {
        assertNull(media3LiveBufferThresholds(live = false, timeshift = false))
        val timeshift = requireNotNull(media3LiveBufferThresholds(live = true, timeshift = true))
        assertEquals(2_500, timeshift.startMs)
        assertEquals(3_500, timeshift.rebufferMs)
        assertTrue(timeshift.startMs < com.ambient.tv.livetv.LIVE_TIMESHIFT_PREROLL_MS) // start stays instant
        val direct = requireNotNull(media3LiveBufferThresholds(live = true, timeshift = false))
        assertEquals(androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, direct.startMs) // zapping as fast as before
        assertTrue(direct.rebufferMs > androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
        // DefaultLoadControl requires both thresholds below its minimum buffer.
        assertTrue(timeshift.rebufferMs <= androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS)
    }

    @Test fun vlcLiveOptionsTolerateJitterAndCacheTheLoopback() {
        assertEquals(emptyList<String>(), vlcLiveMediaOptions(live = false, timeshift = false))
        assertEquals(listOf(":clock-jitter=5000"), vlcLiveMediaOptions(live = true, timeshift = false))
        assertEquals(listOf(":clock-jitter=5000", ":network-caching=3000"), vlcLiveMediaOptions(live = true, timeshift = true))
        assertTrue(VLC_TIMESHIFT_NETWORK_CACHING_MS < com.ambient.tv.livetv.LIVE_TIMESHIFT_PREROLL_MS)
    }

    @Test fun mpvCachesTheTimeshiftLoopbackButNotOtherLocalHosts() {
        val loopback = "http://127.0.0.1:40123/0123456789abcdef0123456789abcdef/0.ts"
        assertTrue(mpvUsesNetworkCache(loopback, false))
        assertFalse(mpvUsesNetworkCache("http://127.0.0.1:40123/proxy", false))
        assertEquals(listOf("cache-pause" to "yes", "cache-pause-wait" to "3"), mpvTimeshiftCacheOptions(true))
        assertEquals(emptyList<Pair<String, String>>(), mpvTimeshiftCacheOptions(false))
    }

    @Test fun routeCallbackBurstProbesOnceAfterItSettles() {
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        var fired = 0
        val debouncer = RouteChangeDebouncer(ROUTE_CHANGE_DEBOUNCE_MS,
            schedule = { task, delay -> scheduled += task to delay },
            cancel = { task -> scheduled.removeAll { it.first === task } },
            fire = { fired++ })
        debouncer.trigger(); debouncer.trigger(); debouncer.trigger() // HDMI gone, back, profile update
        assertEquals(1, scheduled.size)
        assertEquals(ROUTE_CHANGE_DEBOUNCE_MS, scheduled.single().second)
        scheduled.removeAt(0).first.run()
        assertEquals(1, fired)
        debouncer.trigger(); debouncer.close()
        assertTrue(scheduled.isEmpty())
        assertEquals(1, fired)
        assertTrue(ROUTE_CHANGE_DEBOUNCE_MS in 1_000L..2_000L)
    }

    @Test fun routeFlapBackToTheSamePlanDoesNotRestart() {
        AudioCodecRejects.clear()
        val session = AudioSession(com.ambient.tv.data.local.AppPreferences(com.ambient.tv.data.local.memoryPreferences()), "media3", true)
        session.sourceCodec = "ac3"; session.sourceChannels = 6
        val hdmi = AudioRouteCapabilities(setOf("ac3", "eac3"), 8, true)
        session.updateRoute(hdmi)
        var restarts = 0
        session.routeChanged = { _, _ -> restarts++ }
        session.updateRoute(hdmi) // debounced probe after "gone"+"back": same route
        assertEquals(0, restarts)
        session.updateRoute(hdmi.copy(passthrough = emptySet()))
        assertEquals(1, restarts)
    }
}
