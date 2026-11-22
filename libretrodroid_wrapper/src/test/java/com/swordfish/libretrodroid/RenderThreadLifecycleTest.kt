package com.swordfish.libretrodroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderThreadLifecycleTest {
    private val queue = ArrayDeque<() -> Unit>()
    private val calls = mutableListOf<String>()
    private val lifecycle = RenderThreadLifecycle(
        enqueue = { queue.addLast(it) },
        resumeNative = { calls += "resume" },
        pauseNative = { calls += "pause" },
        onReady = { calls += "ready" },
    )

    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }

    @Test fun lifecycleCallbacksReturnBeforeAnyNativeWork() {
        lifecycle.resume()
        lifecycle.pause()
        assertTrue(calls.isEmpty())
        drain()
        assertEquals(listOf("resume", "pause"), calls)
    }

    @Test fun backgroundCheckpointCompletesBeforePauseAndResume() {
        lifecycle.resume()
        drain()
        calls.clear()
        queue += { calls += "checkpoint" }
        lifecycle.pause()
        lifecycle.resume()
        assertTrue(calls.isEmpty())
        drain()
        assertEquals(listOf("checkpoint", "pause", "resume", "ready"), calls)
    }

    @Test fun duplicateEventsDoNotRecreateNativeInputOrRestartAudio() {
        lifecycle.resume()
        lifecycle.resume()
        drain()
        lifecycle.pause()
        lifecycle.pause()
        drain()
        assertEquals(1, calls.count { it == "resume" })
        assertEquals(1, calls.count { it == "pause" })
    }

    @Test fun rapidBackgroundForegroundBackgroundDoesNotResumeFrames() {
        lifecycle.resume()
        lifecycle.pause()
        lifecycle.resume()
        lifecycle.pause()
        drain()
        assertEquals(listOf("resume", "pause", "resume", "pause"), calls)
    }

    @Test fun onlyLatestResumeMayEnableFramesAcrossQueuedPause() {
        lifecycle.resume()
        lifecycle.pause()
        lifecycle.resume()
        queue.removeFirst().invoke()
        assertEquals(listOf("resume"), calls)
        queue.removeFirst().invoke()
        assertEquals(listOf("resume", "pause"), calls)
        queue.removeFirst().invoke()
        assertEquals(listOf("resume", "pause", "resume", "ready"), calls)
    }

    @Test fun backgroundBeforeFirstSurfaceDoesNotPauseUninitializedNativeSession() {
        lifecycle.pause()
        drain()
        assertTrue(calls.isEmpty())
        lifecycle.resume()
        drain()
        assertEquals(listOf("resume", "ready"), calls)
    }
}
