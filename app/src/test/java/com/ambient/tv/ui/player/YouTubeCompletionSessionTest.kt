package com.ambient.tv.ui.player

import com.ambient.tv.youtube.youtubeProgressReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class)
class YouTubeCompletionSessionTest {
    private class Clock : PlaybackHandle {
        override fun status() = PlaybackStatus(positionMs = 40_000L, durationMs = 100_000L, playing = true)
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
    }

    private fun metadata(id: String) = PlaybackMetadata(
        route = "youtube/$id", title = id, source = "youtube", mediaId = id, durationMs = 100_000L,
    )

    @Before fun reset() {
        BackgroundPlaybackSession.stop()
        BackgroundPlaybackSession.takeCompleted()
    }

    @After fun cleanup() = reset()

    @Test fun terminalCompletionSurvivesTheLoadingTransitionAndDoesNotMarkTheNextVideoSeen() {
        val context = RuntimeEnvironment.getApplication()
        BackgroundPlaybackSession.acquire(context, metadata("current"), "fixture", Any()) { Clock() }
        BackgroundPlaybackSession.recordAutomaticNextUp(NextUpCandidate("next", "Next", source = "youtube"), completed = true)
        BackgroundPlaybackSession.beginItemTransition()
        // The decoder never reached EOF: only the accepted terminal SponsorBlock handoff
        // knows that no actual content remains after the current 40% position.
        assertFalse(BackgroundPlaybackSession.snapshot.value.status.ended)
        assertTrue(BackgroundPlaybackSession.snapshot.value.replacingSource)
        BackgroundPlaybackSession.acquire(context, metadata("next"), "fixture", Any()) { Clock() }
        val outgoing = BackgroundPlaybackSession.takeCompleted().single()
        // Service heartbeats ignore pending source replacements, but this is a final
        // outgoing report. Retaining the transition flag would silently drop completion.
        assertFalse(outgoing.replacingSource)
        assertTrue(outgoing.completedByNavigation)
        val queue = PlaybackReportQueue<BackgroundPlaybackSession.Snapshot>()
        queue.put(outgoing.generation, outgoing, stopped = true)
        val queued = requireNotNull(queue.take())
        assertTrue(queued.stopped)
        val finalState = queued.value
        val report = requireNotNull(youtubeProgressReport(finalState.status.positionMs, finalState.status.durationMs,
            ended = finalState.status.ended, completedByNavigation = finalState.completedByNavigation))
        assertTrue(report.completed)
        assertEquals(100_000L, report.positionMs)
        assertFalse(BackgroundPlaybackSession.snapshot.value.completedByNavigation)
    }

    @Test fun manuallyChoosingNextRetainsTheUnfinishedOutgoingPosition() {
        val context = RuntimeEnvironment.getApplication()
        BackgroundPlaybackSession.acquire(context, metadata("current"), "fixture", Any()) { Clock() }
        BackgroundPlaybackSession.recordManualNextUp(NextUpCandidate("next", "Next", source = "youtube"))
        BackgroundPlaybackSession.beginItemTransition()
        BackgroundPlaybackSession.acquire(context, metadata("next"), "fixture", Any()) { Clock() }
        val outgoing = BackgroundPlaybackSession.takeCompleted().single()
        assertFalse(outgoing.replacingSource)
        assertFalse(outgoing.completedByNavigation)
        val report = requireNotNull(youtubeProgressReport(outgoing.status.positionMs, outgoing.status.durationMs,
            ended = outgoing.status.ended, completedByNavigation = outgoing.completedByNavigation))
        assertFalse(report.completed)
        assertEquals(40_000L, report.positionMs)
    }

    @Test fun stoppingWhileTheCompletedSuccessorLoadsKeepsTheFinalOutgoingReport() {
        val context = RuntimeEnvironment.getApplication()
        BackgroundPlaybackSession.acquire(context, metadata("current"), "fixture", Any()) { Clock() }
        BackgroundPlaybackSession.recordAutomaticNextUp(NextUpCandidate("next", "Next", source = "youtube"), completed = true)
        BackgroundPlaybackSession.beginItemTransition()
        assertTrue(BackgroundPlaybackSession.snapshot.value.replacingSource)
        BackgroundPlaybackSession.stop()
        val outgoing = BackgroundPlaybackSession.takeCompleted().single()
        assertFalse(outgoing.replacingSource)
        assertTrue(outgoing.completedByNavigation)
        assertEquals("current", outgoing.metadata?.mediaId)
        assertEquals(40_000L, outgoing.status.positionMs)
    }
}
