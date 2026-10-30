package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.memoryPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOnlyDiagnosticsTest {
    private fun session(audioOnly: Boolean = false): AudioSession =
        AudioSession(AppPreferences(memoryPreferences()), "media3", sourcePassthrough = false, audioOnly = audioOnly)

    @Test fun enteringAudioOnlyClearsVideoDiagnostics() {
        val playback = session()
        playback.performanceHint = true
        playback.showFpsOverlay = true
        playback.showFrameDropOverlay = true

        playback.updateAudioOnly(true)

        assertTrue(playback.audioOnly)
        assertFalse(playback.performanceHint)
        assertFalse(playback.showFpsOverlay)
        assertFalse(playback.showFrameDropOverlay)
    }

    @Test fun audioOnlyRendererRestartDoesNotRestoreVideoDiagnostics() {
        val previous = session().apply {
            performanceHint = true
            showFpsOverlay = true
            showFrameDropOverlay = true
        }
        val resumed = session(audioOnly = true)

        resumed.restoreFromSession(previous)

        assertTrue(resumed.audioOnly)
        assertFalse(resumed.performanceHint)
        assertFalse(resumed.showFpsOverlay)
        assertFalse(resumed.showFrameDropOverlay)
    }

    @Test fun videoRendererRestartPreservesVideoDiagnostics() {
        val previous = session().apply {
            performanceHint = true
            showFpsOverlay = true
            showFrameDropOverlay = true
        }
        val resumed = session()

        resumed.restoreFromSession(previous)

        assertFalse(resumed.audioOnly)
        assertTrue(resumed.performanceHint)
        assertTrue(resumed.showFpsOverlay)
        assertTrue(resumed.showFrameDropOverlay)
    }
}
