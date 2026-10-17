package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.*
import org.junit.Test

class PlaybackDiagnosticsTransferTest {
    @Test fun diagnosticsSurviveRendererAndOutputRestartsWithoutBecomingDefaults() {
        val preferences = AppPreferences(memoryPreferences())
        for (from in listOf("media3", "mpv", "vlc")) {
            for (to in listOf("media3", "mpv", "vlc")) {
                for (stats in listOf(false, true)) for (fps in listOf(false, true)) {
                    val previous = AudioSession(preferences, from, false).apply {
                        performanceHint = stats
                        showFpsOverlay = fps
                    }
                    // acquire() releases the old engine before transferring its session state.
                    previous.release()
                    val next = AudioSession(preferences, to, false)
                    next.restoreFromSession(previous)
                    next.restoreAutomaticProfile(null)
                    assertEquals("$from -> $to stats", stats, next.performanceHint)
                    assertEquals("$from -> $to FPS", fps, next.showFpsOverlay)
                    assertFalse(next.settings.performanceHint)
                    val fresh = AudioSession(preferences, to, false)
                    assertFalse(fresh.performanceHint)
                    assertFalse(fresh.showFpsOverlay)
                }
            }
        }
    }
}
