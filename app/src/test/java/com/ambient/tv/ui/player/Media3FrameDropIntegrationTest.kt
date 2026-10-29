@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Media3FrameDropIntegrationTest {
    private val handles = mutableListOf<Media3PlaybackHandle>()

    private class PlayerFixture {
        var counters = DecoderCounters().apply {
            renderedOutputBufferCount = 100
            droppedBufferCount = 10
        }
        var positionMs = 60_000L
        val listeners = linkedSetOf<Player.Listener>()
        // Raw H.264/H.265 transport streams can expose a valid video format with NO_VALUE FPS.
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "Frame-drop player fixture"
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "getVideoDecoderCounters" -> counters
                "getVideoFormat" -> format
                "getCurrentTracks" -> Tracks.EMPTY
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentPosition" -> positionMs
                "getDuration" -> C.TIME_UNSET
                "getPlaybackState" -> Player.STATE_READY
                "isPlaying", "getPlayWhenReady", "isCurrentMediaItemLive" -> true
                "getPlayerError" -> null
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "clearVideoSurface", "clearMediaItems", "setPlayWhenReady", "release" -> null
                else -> error("Unexpected player call: ${method.name}")
            }
        } as ExoPlayer

        fun emit(event: (Player.Listener) -> Unit) = listeners.toList().forEach(event)
    }

    private fun handle(fixture: PlayerFixture) = Media3PlaybackHandle(fixture.player).also(handles::add)

    private fun nextSecond(fixture: PlayerFixture, rendered: Int = 50, dropped: Int = 0) {
        fixture.counters.renderedOutputBufferCount += rendered
        fixture.counters.droppedBufferCount += dropped
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    @After fun releaseHandles() { handles.forEach(Media3PlaybackHandle::release) }

    @Test fun rawStreamCountersRemainUsefulWithoutFpsOrAnAdvancingLiveWindowPosition() {
        val fixture = PlayerFixture()
        val handle = handle(fixture)
        val first = readFrameDrops(handle)
        nextSecond(fixture, rendered = 40, dropped = 10)
        // A sliding live window update is not a seek, even if its relative position moves back.
        fixture.positionMs = 59_000L
        fixture.emit { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) }
        val second = readFrameDrops(handle)

        assertEquals(first.generation, second.generation)
        val sample = frameDropWindow(first, second)!!
        assertNull(sample.targetFps)
        assertNull(sample.deviationFps)
        assertEquals(40.0, sample.actualFps, 0.001)
        assertEquals(10.0, sample.droppedFps!!, 0.001)
        assertEquals(20.0, sample.counterDropPercent!!, 0.001)
    }

    @Test fun shortPauseBufferAndSeekTransitionsInvalidateTheCounterWindow() {
        val fixture = PlayerFixture()
        val handle = handle(fixture)
        val oldPosition = Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, 60_000, 60_000, C.INDEX_UNSET, C.INDEX_UNSET)
        val newPosition = Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, 90_000, 90_000, C.INDEX_UNSET, C.INDEX_UNSET)
        val transitions: List<() -> Unit> = listOf(
            { fixture.emit { it.onIsPlayingChanged(false); it.onIsPlayingChanged(true) } },
            { fixture.emit { it.onPlaybackStateChanged(Player.STATE_BUFFERING); it.onPlaybackStateChanged(Player.STATE_READY) } },
            { fixture.emit { it.onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_SEEK) } },
        )
        for (transition in transitions) {
            val first = readFrameDrops(handle)
            transition() // Both polls still see READY/playing; the intervening event must survive.
            nextSecond(fixture)
            val second = readFrameDrops(handle)
            assertNotEquals(first.generation, second.generation)
            assertNull(frameDropWindow(first, second))
        }
        val settled = readFrameDrops(handle)
        nextSecond(fixture)
        assertEquals(50.0, frameDropWindow(settled, readFrameDrops(handle))!!.actualFps, 0.001)
    }

    @Test fun counterReplacementStartsANewWindowEvenWhenTotalsDoNotDecrease() {
        val fixture = PlayerFixture()
        val handle = handle(fixture)
        val first = readFrameDrops(handle)
        fixture.counters = DecoderCounters().apply {
            renderedOutputBufferCount = 200
            droppedBufferCount = 20
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val second = readFrameDrops(handle)
        assertNotEquals(first.generation, second.generation)
        assertNull(frameDropWindow(first, second))
        nextSecond(fixture)
        assertEquals(50.0, frameDropWindow(second, readFrameDrops(handle))!!.actualFps, 0.001)
    }
}
