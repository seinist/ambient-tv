package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.PlaybackTimeline
import com.ambient.tv.domain.model.VideoChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises chapter selection together with the real seek coordinator. Both APIs use the
 * original media clock: decoder/transcode offsets are converted only at the player boundary.
 * These tests cover target selection and commits, not decoder EOF callbacks or OSD rendering.
 */
class ChapterSeekRegressionTest {
    private class Session(
        chapterStarts: List<Long>,
        val durationMs: Long,
        var positionMs: Long,
    ) {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        val timeline = PlaybackTimeline(chapterStarts.map { VideoChapter(it) }).normalized(durationMs)
        val commits = mutableListOf<Long>()

        fun step(direction: Int = 1): Long {
            // requestRelative uses the pending target as its base, even if the displayed value
            // has not caught up with an external seek. Use that same base for the chapter delta.
            val delta = checkNotNull(timeline.chapterStepDelta(
                positionMs = seek.pendingTarget ?: positionMs,
                direction = direction,
                durationMs = durationMs,
                navigationPositionMs = seek.chapterPositionMs(positionMs),
            ))
            if (delta != 0L) {
                positionMs = seek.requestRelative(delta, positionMs, durationMs)
                seek.consumeCommit()?.let(commits::add)
            }
            return delta
        }

        fun sample(engineTimelineMs: Long, atMs: Long) {
            now = atMs
            positionMs = seek.displayedPosition(engineTimelineMs)
        }

        fun landBeforeFiftySeconds() {
            assertEquals(25_000L, step())
            sample(48_000, 800)
            sample(48_600, 1_400)
            assertEquals(48_600L, positionMs)
            assertNull(seek.pendingTarget)
        }
    }

    @Test fun skippingTheFinalChapterAfterAKeyframeLandingReachesTheExactMediaEnd() {
        val session = Session(listOf(0, 20_000, 50_000), 90_000, 25_000)
        session.landBeforeFiftySeconds()
        assertEquals(50_000L, session.seek.chapterPositionMs(session.positionMs))
        assertEquals(41_400L, session.step())
        assertEquals(90_000L, session.positionMs)
        assertEquals(listOf(50_000L, 90_000L), session.commits)
    }

    @Test fun anIntermediateChapterUsesTheLogicalChapterButTheActualSeekBase() {
        val session = Session(listOf(0, 20_000, 50_000, 75_000), 120_000, 25_000)
        session.landBeforeFiftySeconds()
        assertEquals(26_400L, session.step())
        assertEquals(75_000L, session.positionMs)
        assertEquals(listOf(50_000L, 75_000L), session.commits)
    }

    @Test fun rapidChapterStepsStopAtTheEndWhileTheDecoderStillReportsTheOldTime() {
        val session = Session(listOf(0, 20_000, 50_000, 75_000), 120_000, 25_000)
        assertEquals(25_000L, session.step())
        session.sample(25_040, 40)
        assertEquals(25_000L, session.step())
        session.sample(25_080, 80)
        assertEquals(45_000L, session.step())
        session.sample(25_120, 120)
        repeat(3) { assertEquals(0L, session.step()) }
        assertEquals(120_000L, session.positionMs)
        assertEquals(120_000L, session.seek.pendingTarget)
        session.seek.consumeCommit(force = true)?.let(session.commits::add)
        assertEquals(listOf(50_000L, 120_000L), session.commits)
    }

    @Test fun approachingAChapterNaturallyStillSelectsThatChapter() {
        val session = Session(listOf(0, 20_000, 50_000, 75_000), 120_000, 48_600)
        assertEquals(1_400L, session.step())
        assertEquals(50_000L, session.positionMs)
        assertEquals(listOf(50_000L), session.commits)
    }

    @Test fun anExplicitBackwardSeekAllowsTheSameChapterToBeSelectedAgain() {
        val session = Session(listOf(0, 20_000, 50_000, 75_000), 120_000, 25_000)
        session.landBeforeFiftySeconds()
        // A user deliberately goes back inside the old anchor's keyframe window.
        session.positionMs = session.seek.requestAbsolute(48_000)
        assertEquals(2_000L, session.step())
        assertEquals(50_000L, session.positionMs)
    }

    @Test fun anExternalPendingSeekUsesItsOwnBaseBeforeTheDisplayCatchesUp() {
        val session = Session(listOf(0, 20_000, 50_000, 75_000), 120_000, 25_000)
        session.seek.followExternalSeek(50_000)
        assertEquals(25_000L, session.positionMs) // No UI position poll has happened yet.
        assertEquals(50_000L, session.seek.pendingTarget)
        assertEquals(25_000L, session.step())
        assertEquals(75_000L, session.positionMs)
        assertEquals(75_000L, session.seek.pendingTarget)
        session.seek.consumeCommit(force = true)?.let(session.commits::add)
        assertEquals(listOf(75_000L), session.commits)
    }

    @Test fun aRapidFinalChapterStepCommitsEofWithoutWaitingForTheSeekDebounce() {
        var now = 0L
        val seek = PlaybackSeekCoordinator(nowMs = { now })
        val length = 100_000L
        val first = seek.requestRelative(20_000L, 30_000L, length)
        assertEquals(50_000L, seek.consumeCommit())

        // A second chapter key before debounce expires must not be left at the earlier
        // keyframe: the logical EOF request goes straight to the decoder's safe end tail.
        now = 30L
        val end = seek.requestRelative(60_000L, first, length)
        assertEquals(length, end)
        assertEquals(length, seek.consumeCommit(force = isPlaybackEndSeek(end, length)))
        assertNull(seek.consumeCommit())
    }

    @Test fun chapterTargetsStayOnTheAbsoluteMediaClockAcrossATranscodeOffset() {
        val offset = 480_000L
        val session = Session(listOf(0, 120_000, 480_000, 600_000), 900_000, offset + 20_000)
        assertEquals(100_000L, session.step())
        assertEquals(600_000L, session.positionMs)
        assertEquals(120_000L, session.positionMs - offset)
        session.sample(offset + 118_000, 800)
        session.sample(offset + 118_600, 1_400)
        assertEquals(598_600L, session.positionMs)
        assertEquals(600_000L, session.seek.chapterPositionMs(session.positionMs))
        assertEquals(301_400L, session.step())
        assertEquals(900_000L, session.positionMs)
        assertEquals(420_000L, session.positionMs - offset)
        // Earlier chapters retain their original timestamps so a transcode can reopen there.
        assertEquals(-420_000L, session.step(-1))
        assertEquals(480_000L, session.positionMs)
        assertEquals(-360_000L, session.step(-1))
        assertEquals(120_000L, session.positionMs)
    }
}
