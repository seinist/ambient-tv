package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    private val target = PlaybackRecoveryRequests.Target("player/42", "plex:server-a:account-a")
    private val handle = Any()
    private val owner = PlaybackRecoveryOwner(7, target.route, target.sourceIdentity, handle)

    @Test fun genericNativeFailuresNeedAProbeButKnownAudioErrorsKeepTheirOwnFallback() {
        assertTrue(ambiguousNativePlaybackFailure("MPV: -13", "VLC cannot play this stream"))
        assertTrue(ambiguousNativePlaybackFailure("VLC cannot play this stream", "VLC cannot play this stream"))
        assertFalse(ambiguousNativePlaybackFailure("MPV audio: -14", "VLC cannot play this stream"))
        assertFalse(ambiguousNativePlaybackFailure("Unsupported audio decoder", "VLC cannot play this stream"))
        assertFalse(ambiguousNativePlaybackFailure("", ""))
    }

    @Test fun lateAutomaticFailureCannotFinishAfterManualRetry() {
        val requests = PlaybackRecoveryRequests()
        val automatic = requests.begin(target, owner)
        val manual = requests.begin(target, owner)
        assertFalse(requests.current(automatic, target, owner))
        requests.finish(automatic)
        assertTrue(requests.current(manual, target, owner))
    }

    @Test fun leavingOrCancellingCannotResumeTheOldTitle() {
        val requests = PlaybackRecoveryRequests()
        val retry = requests.begin(target, owner)
        requests.cancel()
        assertFalse(requests.current(retry, target, owner))
    }

    @Test fun titleAndLoginArePartOfTheRequestEvenWhenNumericIdsCollide() {
        val requests = PlaybackRecoveryRequests()
        val retry = requests.begin(target, owner)
        assertFalse(requests.current(retry, target.copy(route = "player/43"), owner))
        assertFalse(requests.current(retry, target.copy(sourceIdentity = "plex:server-a:account-b"), owner))
        assertFalse(requests.current(retry, target.copy(sourceIdentity = "plex:server-b:account-a"), owner))
    }

    @Test fun aNewSessionOrRendererCannotBePausedByAnOldFailure() {
        assertFalse(owner.matches(owner.copy(generation = 8)))
        assertFalse(owner.matches(owner.copy(handle = Any())))
        assertFalse(owner.matches(owner.copy(replacingSource = true)))
        assertFalse(owner.owns("player/43", target.sourceIdentity))
        assertFalse(owner.owns(target.route, "plex:other-login"))
        assertTrue(owner.matches(owner.copy()))
    }

    @Test fun idleRetryCannotAdoptAnUnrelatedPlaybackStartedDuringProbe() {
        val requests = PlaybackRecoveryRequests()
        val stopped = PlaybackRecoveryOwner(8, null, null, null)
        val retry = requests.begin(target, stopped)
        assertTrue(requests.current(retry, target, stopped))
        assertFalse(requests.current(retry, target, owner.copy(generation = 9)))
    }

    @Test fun tvTunerHandoffTransfersOnlyItsOwnCurrentRecovery() {
        val requests = PlaybackRecoveryRequests()
        val retry = requests.begin(target, owner)
        val replacement = owner.copy(generation = 8, handle = null, replacingSource = true)
        assertFalse(requests.transfer(retry, owner.copy(generation = 6), replacement))
        assertTrue(requests.transfer(retry, owner, replacement))
        assertTrue(requests.current(retry, target, replacement))
        assertFalse(requests.current(retry, target, owner))
        requests.cancel()
        assertFalse(requests.transfer(retry, replacement, owner.copy(generation = 9)))
    }

    @Test fun failedTunerOpenCanPublishItsOwnErrorAfterClosingItsSubscription() {
        val requests = PlaybackRecoveryRequests()
        val replacement = owner.copy(generation = 8, handle = null, replacingSource = true)
        val retry = requests.begin(target, replacement)
        val stopped = PlaybackRecoveryOwner(9, null, null, null)
        assertTrue(requests.transfer(retry, replacement, stopped))
        assertTrue(requests.current(retry, target, stopped))
        assertFalse(requests.current(retry, target, stopped.copy(generation = 10)))
    }

    @Test fun serverOutageKeepsLastLocalPositionWhenDecoderClockFallsToZero() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 87_000, 87_000, 20_000, true, false)
        positions.observe(target.route, target.sourceIdentity, 7, false, 0, 0, 20_000, false, true)
        assertEquals(87_000L, positions.resume(target.route, target.sourceIdentity))
    }

    @Test fun serverConversionRetainsTheAbsoluteTimeline() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 12_000, 312_000, 300_000, true, false)
        positions.observe(target.route, target.sourceIdentity, 7, false, 0, 300_000, 300_000, false, true)
        assertEquals(312_000L, positions.resume(target.route, target.sourceIdentity))
    }

    @Test fun failedOpeningBeforeFirstFrameRetainsRequestedStart() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 0, 0, 123_000, false, true)
        assertEquals(123_000L, positions.resume(target.route, target.sourceIdentity))
    }

    @Test fun explicitBackwardSeekIncludingZeroIsNotReplacedByOldProgress() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 90_000, 90_000, 20_000, true, false)
        positions.observe(target.route, target.sourceIdentity, 7, false, 0, 0, 20_000, false, true, requestedSeek = true)
        assertEquals(0L, positions.resume(target.route, target.sourceIdentity))
    }

    @Test fun sameIdOnAnotherAccountAndAnotherTitleDoNotInheritResume() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 90_000, 90_000, 0, false, true)
        assertNull(positions.resume(target.route, "plex:server-a:account-b"))
        assertNull(positions.resume("player/43", target.sourceIdentity))
    }

    @Test fun retryCheckpointSurvivesStopAndBufferingUntilReplacementActuallyPlays() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 90_000, 90_000, 0, false, true)
        positions.observe(target.route, target.sourceIdentity, 9, false, 0, 0, 90_000, false, false)
        assertEquals(90_000L, positions.resume(target.route, target.sourceIdentity))
        positions.observe(target.route, target.sourceIdentity, 9, false, 90_500, 90_500, 90_000, true, false)
        assertNull(positions.resume(target.route, target.sourceIdentity))
    }

    @Test fun liveElapsedTimeIsNeverUsedAsRecordingResumePosition() {
        val positions = PlaybackRecoveryPositions()
        positions.observe("tvh_player/channel/a", "tvh:a", 7, true, 90_000, 90_000, 0, true, false)
        positions.observe("tvh_player/channel/a", "tvh:a", 7, true, 0, 0, 0, false, true)
        assertNull(positions.resume("tvh_player/channel/a", "tvh:a"))
        positions.observe("tvh_player/recording/a", "tvh:a", 8, false, 90_000, 90_000, 0, false, true)
        assertEquals(90_000L, positions.resume("tvh_player/recording/a", "tvh:a"))
    }

    @Test fun explicitStartFromBeginningClearsRecoveryPoint() {
        val positions = PlaybackRecoveryPositions()
        positions.observe(target.route, target.sourceIdentity, 7, false, 90_000, 90_000, 0, false, true)
        positions.clear(target.route, target.sourceIdentity)
        assertNull(positions.resume(target.route, target.sourceIdentity))
    }
}
