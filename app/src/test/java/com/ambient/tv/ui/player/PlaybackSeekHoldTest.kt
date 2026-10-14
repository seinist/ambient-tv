package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [PlaybackSeekState] the way a held D-pad key does (one key down, a 400 ms pause, then a
 * repeat every 50 ms) against engines that answer position polls the way the three renderers do.
 */
class PlaybackSeekHoldTest {
    /**
     * [keyframeGapMs] 0 lands exactly (Media3); otherwise the seek lands on the keyframe before
     * the target (VLC fast seek, MPV `absolute+keyframes`). While a seek is in flight VLC and
     * MPV already answer with the target; a stale reporter still answers with the old position.
     */
    private class FakeEngine(
        startMs: Long,
        private val keyframeGapMs: Long,
        private val seekLatencyMs: Long,
        private val reportsTargetWhileSeeking: Boolean,
        private val playing: Boolean = true,
    ) {
        private var anchorMs = startMs
        private var anchorAtMs = 0L
        private var target: Long? = null
        private var landsAtMs = 0L

        fun seekTo(targetMs: Long, now: Long) {
            anchorMs = position(now)
            anchorAtMs = now
            target = targetMs
            landsAtMs = now + seekLatencyMs
        }

        fun position(now: Long): Long {
            target?.let { requested ->
                if (now < landsAtMs) return if (reportsTargetWhileSeeking) requested else anchorMs
                anchorMs = if (keyframeGapMs > 0) requested / keyframeGapMs * keyframeGapMs else requested
                anchorAtMs = landsAtMs
                target = null
            }
            return anchorMs + if (playing) now - anchorAtMs else 0L
        }
    }

    private class Run(val shown: List<Long>, val commits: List<Long>, val engineAtEnd: Long, val requested: Long) {
        /** The OSD is back on the engine clock: at most one poll behind it. */
        val followsEngine: Boolean get() = engineAtEnd - shown.last() in 0L..250L
    }

    /** Samples the OSD position every 10 ms while [stepMs] is held for [heldMs], then watched. */
    private fun hold(
        engine: FakeEngine,
        stepMs: Long,
        heldMs: Long,
        pollPhaseMs: Long,
        watchMs: Long = 16_000,
        pollMs: Long = 250,
    ): Run {
        var now = 0L
        val commits = mutableListOf<Long>()
        val seek = PlaybackSeekState(PlaybackSeekCoordinator(nowMs = { now }), engine.position(0)) {
            commits += it
            engine.seekTo(it, now)
        }
        val shown = mutableListOf<Long>()
        var requested = seek.positionMs
        var lastKeyAt = -1L
        for (t in 0L..heldMs + watchMs step 10) {
            now = t
            if (t % pollMs == pollPhaseMs) seek.sync(engine.position(t))
            if (t == 0L || (t in 400..heldMs && (t - 400) % 50 == 0L)) {
                seek.seekBy(stepMs, DURATION_MS)
                requested = (requested + stepMs).coerceIn(0L, DURATION_MS)
                lastKeyAt = t
            }
            if (t == lastKeyAt + PlaybackSeekCoordinator.DEBOUNCE_MS) seek.commitSettled()
            shown += seek.positionMs
        }
        return Run(shown, commits, engine.position(now), requested)
    }

    private fun firstStepBack(shown: List<Long>): String? =
        shown.zipWithNext().withIndex().firstOrNull { (_, pair) ->
            // Landing on a keyframe may sit several seconds behind the request. A drop further
            // than that window is the pre-seek clock taking over.
            pair.first - pair.second > PlaybackSeekCoordinator.ALIGN_BEFORE_MS
        }?.let { (index, pair) -> "at ${(index + 1) * 10} ms: ${pair.first} -> ${pair.second}" }

    private fun assertForwardHoldNeverStepsBack(engine: () -> FakeEngine) {
        for (heldMs in listOf(0L, 300L, 1_000L, 3_000L)) for (phase in 0L until 250L step 30) {
            val run = hold(engine(), stepMs = 30_000, heldMs = heldMs, pollPhaseMs = phase)
            assertEquals("held $heldMs ms, poll phase $phase: OSD stepped back", null, firstStepBack(run.shown))
            assertEquals("held $heldMs ms, poll phase $phase: final target", run.requested, run.commits.last())
            assertTrue("held $heldMs ms, poll phase $phase: engine was spammed ${run.commits}", run.commits.size <= 2)
            assertTrue("held $heldMs ms, poll phase $phase: OSD left the engine", run.followsEngine)
        }
    }

    @Test fun anExactEngineNeverStepsBackDuringAHold() =
        assertForwardHoldNeverStepsBack { FakeEngine(START_MS, 0, 300, reportsTargetWhileSeeking = true) }

    @Test fun aKeyframeEngineThatReportsTheTargetNeverStepsBackDuringAHold() =
        assertForwardHoldNeverStepsBack { FakeEngine(START_MS, 7_000, 200, reportsTargetWhileSeeking = true) }

    @Test fun aKeyframeEngineThatReportsTheOldPositionNeverStepsBackDuringAHold() =
        assertForwardHoldNeverStepsBack { FakeEngine(START_MS, 7_000, 200, reportsTargetWhileSeeking = false) }

    @Test fun aCloseKeyframeNeverStepsBackDuringAHold() =
        assertForwardHoldNeverStepsBack { FakeEngine(START_MS, 2_000, 200, reportsTargetWhileSeeking = true) }

    @Test fun aBackwardHoldNeverStepsForwardAndLandsWithoutAJump() {
        for (phase in 0L until 250L step 30) {
            val heldMs = 2_000L
            val engine = FakeEngine(START_MS, 7_000, 200, reportsTargetWhileSeeking = true)
            val run = hold(engine, stepMs = -10_000, heldMs = heldMs, pollPhaseMs = phase)
            val duringHold = run.shown.take((heldMs / 10).toInt())
            assertTrue("poll phase $phase: OSD stepped forward", duringHold.zipWithNext().all { (a, b) -> b <= a })
            val afterHold = run.shown.drop((heldMs / 10).toInt())
            assertTrue(
                "poll phase $phase: OSD jumped on landing",
                afterHold.zipWithNext().all { (a, b) ->
                    b - a in -PlaybackSeekCoordinator.ALIGN_BEFORE_MS..1_000L
                },
            )
            assertEquals(run.requested, run.commits.last())
            assertTrue("poll phase $phase: OSD left the engine", run.followsEngine)
        }
    }

    @Test fun aPausedPlayerKeepsTheRequestedTimeWhenTheKeyframeIsBehind() {
        val engine = FakeEngine(START_MS, 7_000, 200, reportsTargetWhileSeeking = true, playing = false)
        val run = hold(engine, stepMs = 30_000, heldMs = 0, pollPhaseMs = 0, watchMs = 3_000)
        assertEquals(run.requested, run.shown.last())
        assertTrue(run.engineAtEnd < run.requested)
        assertEquals(listOf(run.requested), run.commits)
    }

    private companion object {
        const val START_MS = 741_300L
        const val DURATION_MS = 7_200_000L
    }
}
