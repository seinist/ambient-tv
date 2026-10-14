package com.ambient.tv.ui.player

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlaybackSeekStopTest {
    class PreferencesApplication : Application(), dagger.hilt.internal.GeneratedComponentManager<Any> {
        private val component by lazy {
            object : dagger.hilt.internal.GeneratedComponent, VideoImagePreferencesEntryPoint {
                private val prefs = com.ambient.tv.data.local.AppPreferences(getSharedPreferences("test", 0))
                override fun preferences() = prefs
                override fun performanceProfileResolver(): com.ambient.tv.ui.performance.PerformanceProfileResolver =
                    error("No renderer in this session test")
            }
        }
        override fun generatedComponent(): Any = component
    }

    private class StaleClock : PlaybackHandle {
        override fun status() = PlaybackStatus(positionMs = 10_000, durationMs = 600_000)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit // Decoder has not acknowledged any seek.
        override fun release() = Unit
    }

    @After fun cleanup() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    @Test fun stopReportsLastUncommittedTargetOnAbsoluteTimeline() {
        val owner = BackgroundPlaybackSession.acquire(RuntimeEnvironment.getApplication(),
            PlaybackMetadata("test", "Seek test", source = "audit", mediaId = "test", timelineOffsetMs = 300_000),
            "fixture", Any()) { StaleClock() }
        val commits = mutableListOf<Long>()
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { 0L }), 310_000) { commits += it }
        BackgroundPlaybackSession.registerSeekState(owner, seek)
        seek.seekTo(280_000)
        seek.seekTo(120_000)
        assertEquals(listOf(280_000L), commits)
        BackgroundPlaybackSession.stop()
        val completed = BackgroundPlaybackSession.takeCompleted().single()
        assertEquals(120_000L, completed.requestedTimelinePositionMs)
        assertEquals(300_000L, completed.metadata!!.timelineOffsetMs)
        BackgroundPlaybackSession.unregisterSeekState(seek)
    }

    @Test fun oldScreenCannotOverrideNewSessionResumePosition() {
        val metadata = PlaybackMetadata("test", "Seek test", source = "audit", mediaId = "test")
        val owner = BackgroundPlaybackSession.acquire(RuntimeEnvironment.getApplication(), metadata,
            "fixture", Any()) { StaleClock() }
        val oldSeek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { 0L }), 0) {}
        BackgroundPlaybackSession.registerSeekState(owner, oldSeek)
        oldSeek.seekTo(400_000)
        BackgroundPlaybackSession.acquire(RuntimeEnvironment.getApplication(), metadata.copy(route = "next", mediaId = "next"),
            "fixture", Any()) { StaleClock() }
        BackgroundPlaybackSession.takeCompleted()
        BackgroundPlaybackSession.registerSeekState(owner, oldSeek)
        BackgroundPlaybackSession.stop()
        assertNull(BackgroundPlaybackSession.takeCompleted().single().requestedTimelinePositionMs)
        BackgroundPlaybackSession.unregisterSeekState(oldSeek)
    }
}
