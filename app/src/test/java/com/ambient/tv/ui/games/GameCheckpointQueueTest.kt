package com.ambient.tv.ui.games

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCheckpointQueueTest {
    @Test fun laterCompletedExitCaptureCannotOvertakeEarlierBackgroundCapture() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writes = mutableListOf<String>()
            val queue = GameCheckpointQueue(executor)
            val background = queue.reserve<String> { writes += it }
            val exit = queue.reserve<String> { writes += it }
            exit.complete("exit")
            assertTrue(writes.isEmpty())
            background.complete("background")
            queue.awaitPendingWrites()
            assertEquals(listOf("background", "exit"), writes)
        } finally { executor.shutdownNow() }
    }

    @Test fun resetAfterBackgroundCaptureCannotResurrectOldAutoState() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            var saved: String? = null
            val queue = GameCheckpointQueue(executor)
            val capture = queue.reserve<String> { saved = it }
            queue.execute { saved = null }
            capture.complete("old auto-state")
            queue.awaitPendingWrites()
            assertEquals(null, saved)
        } finally { executor.shutdownNow() }
    }

    @Test fun slowCaptureReportsIncompleteThenRecoversWhenRendererFinishes() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            var written = false
            val queue = GameCheckpointQueue(executor, captureTimeoutMs = 20, barrierTimeoutMs = 2_000)
            val capture = queue.reserve<String> { written = true }
            assertTrue(runCatching { queue.awaitPendingWrites() }.isFailure)
            assertTrue(capture.pending)
            assertFalse(written)
            assertTrue(capture.complete("late save from the same session"))
            queue.awaitPendingWrites()
            assertTrue(written)
        } finally { executor.shutdownNow() }
    }


    @Test fun lateBackgroundCaptureCannotUndoResetAfterTimeout() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            var saved: String? = null
            val queue = GameCheckpointQueue(executor, captureTimeoutMs = 20, barrierTimeoutMs = 2_000)
            val capture = queue.reserve<String> { saved = it }
            queue.execute { saved = null }
            assertTrue(runCatching { queue.awaitPendingWrites() }.isFailure)
            capture.complete("obsolete auto-state")
            queue.awaitPendingWrites()
            assertEquals(null, saved)
        } finally { executor.shutdownNow() }
    }

    @Test fun rejectedGlQueueDoesNotLeaveNewGameWaitingForCapture() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queue = GameCheckpointQueue(executor, barrierTimeoutMs = 2_000)
            val capture = queue.reserve<String> { error("must not save a failed capture") }
            capture.fail(IllegalStateException("renderer detached"))
            assertTrue(runCatching { queue.awaitPendingWrites() }.isFailure)
            assertFalse(capture.pending)
            queue.awaitPendingWrites() // The reported failure does not poison future sessions.
        } finally { executor.shutdownNow() }
    }
}
