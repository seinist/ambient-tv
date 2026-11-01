package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessQueueTest {
    private class FakeEngine : GaplessQueue.Target {
        val items = mutableListOf("current")
        override var currentIndex = 0
        var paused = false
        override val itemCount get() = items.size
        override fun removeItems(fromIndex: Int, toIndex: Int) {
            repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
            if (currentIndex >= toIndex) currentIndex -= toIndex - fromIndex
        }
        override fun appendItem(id: String, url: String) { items.add(id) }
        override fun pause() { paused = true }
        /** Media3 reached the end of the current item and moved on by itself. */
        fun advance(): String { currentIndex++; return items[currentIndex] }
    }

    private var sleepEndsItem = false
    private var route: String? = "player/a"
    private val continued = mutableListOf<Pair<String, String>>()
    private var sessionAccepts = true
    private val launched = mutableListOf<suspend () -> Unit>()

    private val queue = GaplessQueue(
        endOfItemSleep = { sleepEndsItem },
        sessionRoute = { route },
        continueSession = { from, metadata ->
            if (sessionAccepts) { continued += from to metadata.route; route = metadata.route }
            sessionAccepts
        },
        launch = { launched += it },
    )

    private fun entry(id: String, from: String = "player/a") = GaplessQueue.Entry(
        ratingKey = id, streamUrl = "http://server/$id", fromRoute = from,
        metadata = PlaybackMetadata(route = "player/$id", title = id, source = "plex", mediaId = id),
        continuation = {},
    )

    @Test fun queuedSongSurvivesLeavingTheScreen() {
        val engine = FakeEngine()
        queue.attachScreen(engine)
        queue.offer(entry("b"))
        assertEquals(listOf("current", "b"), engine.items)
        queue.detachScreen(engine)
        assertEquals(listOf("current", "b"), engine.items)
        assertFalse(queue.screenAttached)
    }

    @Test fun withoutScreenTheQueueMovesTheSessionOnAndPreparesTheNextSong() {
        val engine = FakeEngine()
        queue.bind(engine)
        queue.offer(entry("b"))
        assertTrue(queue.onAutoTransition(engine, engine.advance()))
        assertEquals(listOf("player/a" to "player/b"), continued)
        assertEquals(listOf("b"), engine.items)
        assertEquals(0, engine.currentIndex)
        assertNull(queue.entry)
        assertEquals(1, launched.size)
        assertFalse(engine.paused)
        // The continuation queues the following song behind the adopted one.
        queue.offer(entry("c", from = "player/b"))
        assertEquals(listOf("b", "c"), engine.items)
    }

    @Test fun withScreenTheScreenAdopts() {
        val engine = FakeEngine()
        queue.attachScreen(engine)
        queue.offer(entry("b"))
        assertFalse(queue.onAutoTransition(engine, engine.advance()))
        assertTrue(continued.isEmpty())
        assertTrue(launched.isEmpty())
    }

    @Test fun unknownOrRefusedSongPausesInsteadOfPlayingUnderTheOldTitle() {
        val engine = FakeEngine()
        queue.bind(engine)
        engine.items += "x"
        assertFalse(queue.onAutoTransition(engine, engine.advance()))
        assertTrue(engine.paused)

        val other = FakeEngine()
        queue.bind(other)
        queue.offer(entry("b"))
        sessionAccepts = false
        assertFalse(queue.onAutoTransition(other, other.advance()))
        assertTrue(other.paused)
        assertTrue(launched.isEmpty())
    }

    @Test fun stopAfterThisItemKeepsNothingQueued() {
        val engine = FakeEngine()
        queue.bind(engine)
        queue.offer(entry("b"))
        sleepEndsItem = true
        queue.refresh()
        assertEquals(listOf("current"), engine.items)
        sleepEndsItem = false
        queue.refresh()
        assertEquals(listOf("current", "b"), engine.items)
    }

    @Test fun songOfAnotherPlaybackIsNotQueued() {
        val engine = FakeEngine()
        queue.bind(engine)
        queue.offer(entry("b", from = "player/other"))
        assertEquals(listOf("current"), engine.items)
        route = null // stopped
        queue.offer(entry("b"))
        assertEquals(listOf("current"), engine.items)
    }

    @Test fun clearingRemovesTheQueuedSong() {
        val engine = FakeEngine()
        queue.bind(engine)
        queue.offer(entry("b"))
        queue.offer(null)
        assertEquals(listOf("current"), engine.items)
    }

    @Test fun releasedEngineForgetsEverything() {
        val engine = FakeEngine()
        queue.attachScreen(engine)
        queue.offer(entry("b"))
        queue.unbind(engine)
        assertNull(queue.target)
        assertNull(queue.entry)
        assertFalse(queue.screenAttached)
        assertFalse(queue.onAutoTransition(engine, engine.advance()))
        assertTrue(continued.isEmpty())
        // Unbinding another engine leaves the current one alone.
        val next = FakeEngine()
        queue.bind(next)
        queue.unbind(engine)
        assertTrue(queue.target === next)
    }
}
