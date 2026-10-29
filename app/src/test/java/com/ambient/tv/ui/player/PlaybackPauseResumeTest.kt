package com.ambient.tv.ui.player

import android.app.Application
import com.ambient.tv.companion.CompanionPlaybackControls
import com.ambient.tv.data.local.AppPreferences
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class PlaybackPauseResumeTest {
    private lateinit var application: Application
    private lateinit var preferences: AppPreferences
    private val sourceOwners = mutableListOf<Any>()

    private class FakePlaybackHandle : PlaybackHandle {
        var positionMs = 60_000L
        var playing = true
        var released = false
        var reopenVersion = 0
        var seekable = true
        val events = mutableListOf<String>()

        override fun status() = PlaybackStatus(positionMs = positionMs, durationMs = 600_000L, playing = playing)
        override fun reopenGeneration() = reopenVersion
        override fun canSeekNow() = seekable
        override fun play() { events += "play"; playing = true }
        override fun pause() { events += "pause"; playing = false }
        override fun seekTo(positionMs: Long) { events += "seek:$positionMs"; this.positionMs = positionMs }
        override fun release() { events += "release"; released = true; playing = false }
    }

    private data class PendingSeek(
        val targetMs: Long,
        val stillCurrent: () -> Boolean,
        val complete: (Boolean) -> Unit,
    )

    private data class DeferredResume(val player: FakePlaybackHandle, val request: PendingSeek)

    @Before fun setUp() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("test", 0).edit().clear().commit()
        preferences = videoImagePreferences(application)
        preferences.pauseRewindSeconds = 5
        preferences.pauseRewindMinPauseSeconds = 10
    }

    @After fun tearDown() {
        sourceOwners.forEach(CompanionPlaybackControls::remove)
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
        preferences.pauseRewindSeconds = 0
    }

    private fun metadata(
        hasVideo: Boolean = true,
        source: String = "plex",
        offsetMs: Long = 0L,
    ) = PlaybackMetadata(
        route = "pause-resume-test", title = "Pause rewind test", source = source, mediaId = "video",
        hasVideo = hasVideo, durationMs = 600_000L, timelineOffsetMs = offsetMs,
    )

    private fun acquire(metadata: PlaybackMetadata = metadata(), backend: String = "fixture") =
        BackgroundPlaybackSession.acquire(application, metadata, backend, Any()) { FakePlaybackHandle() }

    private fun pauseLongEnough() {
        BackgroundPlaybackSession.userPause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }

    private fun deferredResume(offsetMs: Long = 0L): DeferredResume {
        val player = acquire(metadata(offsetMs = offsetMs))
        val requests = mutableListOf<PendingSeek>()
        val owner = Any().also(sourceOwners::add)
        CompanionPlaybackControls.update(owner, "pause-resume-test", CompanionPlaybackControls.SourceControls(
            resumeSeek = { target, stillCurrent, complete -> requests += PendingSeek(target, stillCurrent, complete) },
        ))
        pauseLongEnough()
        player.events.clear()
        BackgroundPlaybackSession.userPlay()
        assertFalse(player.playing)
        assertTrue(player.events.isEmpty())
        val request = requests.single()
        assertTrue(request.stillCurrent())
        return DeferredResume(player, request)
    }

    @Test fun userPauseSeeksBeforePlayingAndConsumesTheJumpOnce() {
        val player = acquire()
        pauseLongEnough()
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("pause", "seek:55000", "play"), player.events)
        assertEquals(55_000L, player.positionMs)
        BackgroundPlaybackSession.userPlay()
        assertEquals(1, player.events.count { it.startsWith("seek:") })
        assertTrue(player.playing)
    }

    @Test fun configuredMinimumPauseIsRespectedByTheSession() {
        val player = acquire()
        BackgroundPlaybackSession.userPause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("pause", "play"), player.events)
        player.events.clear()
        pauseLongEnough()
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("pause", "seek:55000", "play"), player.events)
    }

    @Test fun technicalPauseAndPlayDoNotArmOrRetainAUserJump() {
        val player = acquire()
        BackgroundPlaybackSession.pause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        BackgroundPlaybackSession.play()
        pauseLongEnough()
        BackgroundPlaybackSession.pause() // HDMI/audio-focus handling cancels the user pause context.
        BackgroundPlaybackSession.userPlay()
        assertFalse(player.events.any { it.startsWith("seek:") })
        assertTrue(player.playing)
    }

    @Test fun beginningASeekPreviewCancelsTheJumpBeforeAnyDecoderSeek() {
        val player = acquire()
        pauseLongEnough()
        val seekVersion = BackgroundPlaybackSession.externalSeekVersion
        BackgroundPlaybackSession.userSeek()
        assertEquals(seekVersion + 1, BackgroundPlaybackSession.externalSeekVersion)
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("pause", "play"), player.events)
        assertEquals(60_000L, player.positionMs)
    }

    @Test fun deferredSourceSeekUsesAbsoluteTimeAndWaitsForPreparationBeforePlaying() {
        val (player, request) = deferredResume(offsetMs = 300_000L)
        assertEquals(355_000L, request.targetMs)
        player.seekTo(request.targetMs - 300_000L)
        request.complete(true)
        assertEquals(listOf("seek:55000", "play"), player.events)
        assertFalse(request.stillCurrent())
        request.complete(true)
        assertEquals(1, player.events.count { it == "play" })
    }

    private fun assertDeferredResumeCancelled(cancel: () -> Unit) {
        val (player, request) = deferredResume()
        cancel()
        assertFalse(request.stillCurrent())
        player.events.clear()
        request.complete(true)
        assertTrue("A stale preparation callback restarted playback", player.events.isEmpty())
        assertFalse(player.playing)
    }

    @Test fun anotherUserPauseCancelsDeferredResume() =
        assertDeferredResumeCancelled(BackgroundPlaybackSession::userPause)

    @Test fun toggleWhilePreparingActsAsPause() =
        assertDeferredResumeCancelled(BackgroundPlaybackSession::userToggle)

    @Test fun rendererChoiceCancelsBeforeTheNewEngineIsAcquired() =
        assertDeferredResumeCancelled(BackgroundPlaybackSession::cancelResumeRewind)

    @Test fun repeatedPlayWaitsForTheSamePreparedSeek() {
        val (player, request) = deferredResume()
        BackgroundPlaybackSession.userPlay()
        assertTrue(player.events.isEmpty())
        assertTrue(request.stillCurrent())
        request.complete(true)
        assertEquals(listOf("play"), player.events)
    }

    @Test fun internalReopenDuringPreparationDiscardsTheRequestWithoutBlockingLaterPlay() {
        val (player, request) = deferredResume()
        player.reopenVersion++
        assertFalse(request.stillCurrent())
        request.complete(false)
        assertTrue(player.events.isEmpty())
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("play"), player.events)
    }

    @Test fun seekPreviewCancelsDeferredResume() =
        assertDeferredResumeCancelled(BackgroundPlaybackSession::userSeek)

    @Test fun stopCancelsDeferredResume() =
        assertDeferredResumeCancelled { BackgroundPlaybackSession.stop() }

    @Test fun failedSeekStillHonoursTheCurrentPlayRequestAtTheOldPosition() {
        val (player, request) = deferredResume()
        request.complete(false)
        assertEquals(listOf("play"), player.events)
        assertEquals(60_000L, player.positionMs)
        assertTrue(player.playing)
    }

    @Test fun rendererReplacementClearsAnArmedPauseJump() {
        val previous = acquire()
        pauseLongEnough()
        val replacement = acquire(backend = "other-renderer")
        assertTrue(previous.released)
        assertFalse(replacement.playing)
        replacement.events.clear() // The normal renderer hand-off restored its position and pause.
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("play"), replacement.events)
        assertEquals(60_000L, replacement.positionMs)
    }

    @Test fun rendererReplacementInvalidatesDelayedPreparationFromItsPredecessor() {
        val (_, request) = deferredResume()
        val replacement = acquire(backend = "other-renderer")
        assertFalse(request.stillCurrent())
        replacement.events.clear()
        request.complete(true)
        assertTrue(replacement.events.isEmpty())
        assertFalse(replacement.playing)
    }

    @Test fun internalStreamReopenDoesNotReuseTheEarlierPause() {
        val player = acquire()
        pauseLongEnough()
        player.reopenVersion++
        player.events.clear()
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("play"), player.events)
    }

    @Test fun audioAndSpotifyNeverGetVideoPauseRewind() {
        for (item in listOf(metadata(hasVideo = false), metadata(source = "spotify"))) {
            val player = acquire(item)
            pauseLongEnough()
            player.events.clear()
            BackgroundPlaybackSession.userPlay()
            assertEquals(listOf("play"), player.events)
            assertEquals(60_000L, player.positionMs)
            BackgroundPlaybackSession.stop()
        }
    }

    @Test fun aSourceCallbackAloneDoesNotMakeAnUnseekableFileSeekable() {
        val player = acquire()
        player.seekable = false
        val owner = Any().also(sourceOwners::add)
        var requests = 0
        CompanionPlaybackControls.update(owner, "pause-resume-test", CompanionPlaybackControls.SourceControls(
            resumeSeek = { _, _, complete -> requests++; complete(false) },
        ))
        assertFalse(BackgroundPlaybackSession.resumeCapabilities().pauseRewind)
        pauseLongEnough()
        BackgroundPlaybackSession.userPlay()
        assertEquals(0, requests)
        assertEquals(listOf("pause", "play"), player.events)
    }

    @Test fun aRealServerReloadCanResumeWithoutANativeSeekIndex() {
        val player = acquire()
        player.seekable = false
        val owner = Any().also(sourceOwners::add)
        CompanionPlaybackControls.update(owner, "pause-resume-test", CompanionPlaybackControls.SourceControls(
            resumeSeekCanReload = true,
            resumeSeek = { target, stillCurrent, complete ->
                assertTrue(stillCurrent())
                player.seekTo(target)
                complete(true)
            },
        ))
        assertTrue(BackgroundPlaybackSession.resumeCapabilities().pauseRewind)
        pauseLongEnough()
        BackgroundPlaybackSession.userPlay()
        assertEquals(listOf("pause", "seek:55000", "play"), player.events)
    }
}
