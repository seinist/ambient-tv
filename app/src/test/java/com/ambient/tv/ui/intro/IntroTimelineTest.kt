package com.ambient.tv.ui.intro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IntroTimelineTest {
    @Test fun theExitFadeFitsInsideTheFiveSecondIntro() = runTest {
        var fadeStartedAt = -1L
        var finishedAt = -1L
        launch {
            runIntroTimeline(0, { currentTime }, still = false, awaitSkip = { awaitCancellation() }) { duration ->
                fadeStartedAt = currentTime
                assertEquals(700, duration)
                delay(duration.toLong())
            }
            finishedAt = currentTime
        }
        advanceUntilIdle()
        assertEquals(4_300L, fadeStartedAt)
        assertEquals(5_000L, finishedAt)
    }

    @Test fun aPausedOrSlowedAnimationCannotExtendTheDeadline() = runTest {
        var fadeCancelled = false
        var finished = false
        launch {
            runIntroTimeline(0, { currentTime }, still = false, awaitSkip = { awaitCancellation() }) {
                try {
                    awaitCancellation()
                } finally {
                    fadeCancelled = true
                }
            }
            finished = true
        }
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(finished)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(finished)
        assertTrue(fadeCancelled)
    }

    @Test fun skippingLeavesIndependentWakeAndVpnJobsRunning() = runTest {
        val wake = backgroundScope.launch { awaitCancellation() }
        val vpn = backgroundScope.launch { awaitCancellation() }
        val skip = CompletableDeferred<Unit>()
        var finishedAt = -1L
        launch {
            runIntroTimeline(0, { currentTime }, still = false, awaitSkip = { skip.await() }) { duration ->
                assertEquals(220, duration)
                delay(duration.toLong())
            }
            finishedAt = currentTime
        }
        advanceTimeBy(800)
        skip.complete(Unit)
        advanceUntilIdle()
        assertEquals(1_020L, finishedAt)
        assertTrue(wake.isActive)
        assertTrue(vpn.isActive)
    }

    @Test fun aLateStartUsesOnlyTheRemainingFadeBudget() = runTest {
        advanceTimeBy(4_800)
        var fadeDuration = -1
        launch {
            runIntroTimeline(0, { currentTime }, still = false,
                awaitSkip = { error("The intro is already in its fade interval") }) { duration ->
                fadeDuration = duration
                delay(duration.toLong())
            }
        }
        advanceUntilIdle()
        assertEquals(200, fadeDuration)
        assertEquals(5_000L, currentTime)
    }

    @Test fun anExpiredDeadlineNeverStartsAnotherAnimation() = runTest {
        advanceTimeBy(5_100)
        runIntroTimeline(0, { currentTime }, still = false,
            awaitSkip = { error("Expired intro must not wait") },
            fadeOut = { error("Expired intro must not animate") })
        assertEquals(5_100L, currentTime)
    }

    @Test fun disposingTheOverlayDoesNotFinishOrCancelAnotherConnection() = runTest {
        val wake = backgroundScope.launch { awaitCancellation() }
        var skipListenerCancelled = false
        var finished = false
        val intro = launch {
            runIntroTimeline(0, { currentTime }, still = false, awaitSkip = {
                try {
                    awaitCancellation()
                } finally {
                    skipListenerCancelled = true
                }
            }) { delay(it.toLong()) }
            finished = true
        }
        advanceTimeBy(900)
        intro.cancel()
        runCurrent()
        assertTrue(skipListenerCancelled)
        assertFalse(finished)
        assertTrue(wake.isActive)
    }

    @Test fun lowPowerModeKeepsItsShortFadeWithinTheSameLimit() = runTest {
        runIntroTimeline(0, { currentTime }, still = true, awaitSkip = { awaitCancellation() }) { duration ->
            assertEquals(220, duration)
            delay(duration.toLong())
        }
        assertEquals(4_520L, currentTime)
    }

    @Test fun allDrawingRevealsFinishBeforeTheExitFadeStarts() {
        assertEquals(0f, IntroTiming.animationSeconds(0), 0.0001f)
        assertEquals(0f, IntroTiming.animationSeconds(-50), 0.0001f)
        // The last Prism reveal finishes at drawing second 5.7; the camera settles at second 6.
        assertEquals(6f, IntroTiming.animationSeconds(4_300), 0.0001f)
        assertTrue(IntroTiming.animationSeconds(5_000) > 6f)
    }
}
