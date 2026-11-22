package com.ambient.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaybackResumeTest {
    @Test fun unresolvedMetadataWaitsAndTheFirstResolvedResumeIsAppliedOnce() {
        val resume = YouTubePlaybackResume(initialStartMs = 0L, initialExternalSeekVersion = 8)
        assertNull(resume.resolve(false, 0L, resolved = false, externalSeekVersion = 8))
        assertEquals(30_000L, resume.resolve(false, 30_000L, resolved = true, externalSeekVersion = 8))
        assertNull(resume.resolve(false, 60_000L, resolved = true, externalSeekVersion = 8))
    }

    @Test fun aUserSeekPermanentlyVetoesLateHistoryForThisVideo() {
        val resume = YouTubePlaybackResume(0L, 8)
        resume.userSeek()
        assertNull(resume.resolve(false, 30_000L, resolved = true, externalSeekVersion = 8))
        assertNull(resume.resolve(false, 60_000L, resolved = true, externalSeekVersion = 8))
    }

    @Test fun aRemoteSeekOrUncommittedPreviewVetoesHistoryWithoutWaitingForAnEffect() {
        val resume = YouTubePlaybackResume(0L, 8)
        // The session version changes at KeyDown, before the renderer receives a committed seek.
        assertNull(resume.resolve(false, 30_000L, resolved = true, externalSeekVersion = 9))
    }

    @Test fun anAutomaticSponsorSkipDoesNotCountAsAUserSeek() {
        val resume = YouTubePlaybackResume(0L, 8)
        // Automatic seeks change the committed-seek counter, not externalSeekVersion.
        assertEquals(30_000L, resume.resolve(false, 30_000L, resolved = true, externalSeekVersion = 8))
    }

    @Test fun aResolvedZeroCannotBeReplacedByOldProgressAfterAnOutputRestart() {
        val resume = YouTubePlaybackResume(0L, 8)
        assertNull(resume.resolve(false, 0L, resolved = true, externalSeekVersion = 8))
        assertNull(resume.resolve(false, 60_000L, resolved = true, externalSeekVersion = 8))
    }

    @Test fun aConstructorResumeAndLivePlaybackNeverReceiveAnotherHistorySeek() {
        assertNull(YouTubePlaybackResume(30_000L, 8).resolve(false, 60_000L, true, 8))
        assertNull(YouTubePlaybackResume(0L, 8).resolve(true, 60_000L, true, 8))
    }

    @Test fun nextUpStartsWithItsOwnExternalSeekBaseline() {
        val first = YouTubePlaybackResume(0L, 8)
        first.userSeek()
        assertNull(first.resolve(false, 30_000L, true, 9))
        val next = YouTubePlaybackResume(0L, 9)
        assertEquals(15_000L, next.resolve(false, 15_000L, true, 9))
    }
}
