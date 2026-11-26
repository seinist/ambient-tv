package com.ambient.tv.ui.games

import com.swordfish.libretrodroid.GameRenderEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameRenderEventsTest {
    @Test fun lateCollectorRetainsSurfaceAndLatestFrameIndependently() = runTest {
        val stream = GameRenderEvents("surface", "frame")
        stream.surfaceCreated()
        repeat(100) { stream.frameRendered(it * 100_000_000L) }
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.take(2).toList(seen)
        }
        runCurrent()
        assertEquals(setOf("surface", "frame"), seen.toSet())
    }

    @Test fun surfaceRecreationResetsFrameThrottle() = runTest {
        val stream = GameRenderEvents("surface", "frame")
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.toList(seen)
        }
        runCurrent()
        stream.surfaceCreated()
        runCurrent()
        stream.frameRendered(0)
        runCurrent()
        stream.frameRendered(1)
        runCurrent()
        assertEquals(1, seen.count { it == "frame" })
        stream.surfaceCreated()
        runCurrent()
        stream.frameRendered(2)
        runCurrent()
        assertEquals(2, seen.count { it == "surface" })
        assertEquals(2, seen.count { it == "frame" })
    }

    @Test fun newSurfaceDoesNotReplayFrameFromPreviousGeneration() = runTest {
        val stream = GameRenderEvents("surface", "frame")
        stream.surfaceCreated()
        stream.frameRendered(0)
        stream.surfaceCreated()
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.toList(seen)
        }
        runCurrent()
        assertEquals(listOf("surface"), seen)
        stream.frameRendered(1)
        runCurrent()
        assertEquals(listOf("surface", "frame"), seen)
    }

    @Test fun replacementWhileCollectorIsSuspendedDiscardsOldFrame() = runTest {
        val stream = GameRenderEvents("surface", "frame")
        stream.surfaceCreated()
        stream.frameRendered(0)
        val resume = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.collect { event ->
                seen += event
                if (seen.size == 1) resume.await()
            }
        }
        runCurrent()
        stream.surfaceCreated()
        resume.complete(Unit)
        runCurrent()
        assertEquals(listOf("surface", "surface"), seen)
        stream.frameRendered(1)
        runCurrent()
        assertEquals(listOf("surface", "surface", "frame"), seen)
    }

}
