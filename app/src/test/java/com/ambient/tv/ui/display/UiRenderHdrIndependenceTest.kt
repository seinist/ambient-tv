package com.ambient.tv.ui.display

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Checks real Window requests and renderer boundaries, not a physical TV's HDR output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UiRenderHdrIndependenceTest {
    private val eligible = UiHdrEligibility(35, true, true, true, true, true, 2f)
    private val request = SdrToHdrRequest(400, displayId = 0, displayModeId = 1, referenceWhiteNits = 203)

    @Test fun sdrEmbeddedCanvasStillForwardsVideoConversionWithoutStoppingPlayback() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val window = activity.get().window
            val state = UiHdrState(window, canvasHdrSupported = false)
            val player = RecordingPlayer()
            try {
                state.update(eligible, "video")
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                state.update(eligible, "video")
                // The outer HDR request has actually settled; headroom=1 is not just startup.
                assertEquals(ActivityInfo.COLOR_MODE_HDR, window.colorMode)
                assertEquals(1f, state.headroom, 0f)
                assertFalse(state.failed)

                state.updateVideoConversion(player, request, enabled = true)
                assertEquals(listOf(request), player.requests)
                assertEquals(SdrToHdrStatus.ACTIVE, state.sdrToHdrStatus)
                assertEquals(1f, state.headroom, 0f)

                state.reset()
                assertEquals(listOf(request, null), player.requests)
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
            } finally {
                state.reset()
            }
        }
    }

    @Test fun onlyCanvasAmbientOutputIsBlockedWhileNativeAmbientSurfaceRemainsEligible() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val window = activity.get().window
            val state = UiHdrState(window, canvasHdrSupported = false)
            val canvasRequests = mutableListOf<SdrToHdrRequest?>()
            val nativeRequests = mutableListOf<SdrToHdrRequest?>()
            val canvas = recordingLease(windowOutput = true, canvasRequests)
            val native = recordingLease(windowOutput = false, nativeRequests)
            try {
                state.attachAmbient(canvas)
                state.updateAmbient(eligible, request, "canvas")
                assertEquals(listOf<SdrToHdrRequest?>(null), canvasRequests)
                assertEquals(1f, state.headroom, 0f)
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
                assertFalse(state.failed)

                state.detachAmbient(canvas)
                state.attachAmbient(native)
                state.updateAmbient(eligible, request, "native")
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                state.updateAmbient(eligible, request, "native")
                assertEquals(listOf(request, request), nativeRequests)
                assertEquals(ActivityInfo.COLOR_MODE_HDR, window.colorMode)
                assertEquals(1f, state.headroom, 0f)
                assertFalse(state.failed)
            } finally {
                state.detachAmbient(canvas)
                state.detachAmbient(native)
                state.reset()
            }
        }
    }

    private fun recordingLease(windowOutput: Boolean, requests: MutableList<SdrToHdrRequest?>) =
        AmbientHdrLease(windowOutput = windowOutput, priority = 1) { request ->
            requests += request
            if (request == null) SdrToHdrStatus.OFF else SdrToHdrStatus.ACTIVE
        }

    private class RecordingPlayer : PlaybackHandle {
        val requests = mutableListOf<SdrToHdrRequest?>()
        override fun updateSdrToHdr(request: SdrToHdrRequest?): SdrToHdrStatus {
            requests += request
            return if (request == null) SdrToHdrStatus.OFF else SdrToHdrStatus.ACTIVE
        }
        override fun status() = PlaybackStatus(playing = true, positionMs = 30_000L, durationMs = 300_000L)
        override fun play(): Unit = error("UI HDR must not restart playback")
        override fun pause(): Unit = error("UI HDR must not pause playback")
        override fun seekTo(positionMs: Long): Unit = error("UI HDR must not seek playback")
        override fun detachVideoOutput(): Unit = error("UI HDR must not detach the video output")
        override fun release(): Unit = error("UI HDR must not release playback")
    }
}
