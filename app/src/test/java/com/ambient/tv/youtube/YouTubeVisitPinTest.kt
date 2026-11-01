package com.ambient.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cached tiles stay still for a visit; new videos arrive with the next opening of the page. */
class YouTubeVisitPinTest {
    private val key = "feed:home"
    private val cachedRows = listOf(
        YouTubeRow("Empfohlen", listOf(item("a"), item("b"), item("c"))),
        YouTubeRow("Musik", listOf(item("m1"), item("m2"))),
    )
    private val freshRows = listOf(
        YouTubeRow("Empfohlen", listOf(item("x"), item("y"), item("z"))),
        YouTubeRow("Neu", listOf(item("n1"))),
    )

    @Test fun visibleRowsStayUnchangedAfterBackgroundRefresh() {
        val cache = YouTubeContentCache()
        val revision = cache.revision()
        val visible = cache.merge(key, cachedRows)
        val pin = YouTubeVisitPin()

        assertTrue(pin.begin(key, revision, visible, explicit = false))
        val stored = cache.replace(key, freshRows, revision)
        val shown = youtubeRowsAfterRefresh(visible, stored, pin.holds(key, cache.revision()))

        assertSame(visible, shown)
        assertEquals(listOf("a", "b", "c"), shown.first().items.map { it.videoId })
        assertEquals(listOf("Empfohlen", "Musik"), shown.map { it.id })
    }

    @Test fun nextOpeningShowsTheRefreshedRows() {
        val cache = YouTubeContentCache()
        val revision = cache.revision()
        val visible = cache.merge(key, cachedRows)
        YouTubeVisitPin().begin(key, revision, visible, explicit = false)
        cache.replace(key, freshRows, revision)

        // A new visit (new screen or next app start) reads the cache first.
        val reopened = cache.get(key).orEmpty()
        assertEquals(listOf("x", "y", "z"), reopened.first { it.id == "Empfohlen" }.items.map { it.videoId })
        assertEquals(setOf("Empfohlen", "Neu"), reopened.map { it.id }.toSet())
        val nextVisit = YouTubeVisitPin()
        assertTrue(nextVisit.begin(key, revision, reopened, explicit = false))
        assertEquals(reopened, youtubeRowsAfterRefresh(reopened, reopened, nextVisit.holds(key, revision)))
    }

    @Test fun withoutCacheFreshRowsAppearAsTheyArrive() {
        val cache = YouTubeContentCache()
        val pin = YouTubeVisitPin()

        assertFalse(pin.begin(key, cache.revision(), emptyList(), explicit = false))
        val stored = cache.replace(key, freshRows, cache.revision())
        assertEquals(stored, youtubeRowsAfterRefresh(emptyList(), stored, pin.holds(key, cache.revision())))
    }

    @Test fun explicitActionsAndAccountChangesApplyImmediately() {
        val cache = YouTubeContentCache()
        val pin = YouTubeVisitPin()

        assertFalse(pin.begin(key, cache.revision(), cachedRows, explicit = true))
        assertFalse(pin.holds(key, cache.revision()))

        assertTrue(pin.begin(key, cache.revision(), cachedRows, explicit = false))
        cache.clear() // sign-in, sign-out or another account/language scope
        assertFalse(pin.holds(key, cache.revision()))
        assertFalse(pin.holds("feed:subscriptions", cache.revision()))
    }

    @Test fun paginationStillAppendsToThePinnedRows() {
        val cache = YouTubeContentCache()
        val revision = cache.revision()
        val visible = cache.merge(key, cachedRows)
        cache.replace(key, freshRows, revision)
        val page = listOf(item("c"), item("d"), item("e"))
        val cached = cache.appendItems(key, "Empfohlen", page, revision)

        val pinned = youtubeRowsAfterContinuation(visible, cached, "Empfohlen", page, pinned = true)
        assertEquals(listOf("a", "b", "c", "d", "e"), pinned.first().items.map { it.videoId })
        assertEquals(cachedRows[1], pinned[1])
        // The cache still follows the newest feed for the next opening.
        assertEquals(listOf("x", "y", "z", "c", "d", "e"), cache.get(key)!!.first().items.map { it.videoId })

        val unpinned = youtubeRowsAfterContinuation(visible, cached, "Empfohlen", page, pinned = false)
        assertSame(cached, unpinned)
    }

    @Test fun homeRowsFollowTheSameRule() {
        val pins = HomeYouTubePins()

        pins.startLoad(HomeYouTubeLoadMode.OPEN)
        assertTrue(pins.mayShowCached("home", HomeYouTubeLoadMode.OPEN, visible = false))
        pins.cachedShown("home", HomeYouTubeLoadMode.OPEN)
        assertFalse(pins.mayApplyFresh("home", visible = true))
        // Without a cached row the fresh feed still appears as it arrives.
        assertTrue(pins.mayApplyFresh("subscriptions", visible = false))
        assertTrue(pins.mayApplyFresh("subscriptions", visible = true))

        // Timer, reconnect or retry while Home stays open: nothing visible changes.
        pins.startLoad(HomeYouTubeLoadMode.BACKGROUND)
        assertFalse(pins.mayShowCached("home", HomeYouTubeLoadMode.BACKGROUND, visible = true))
        assertFalse(pins.mayApplyFresh("home", visible = true))

        // Opening Home again shows the cache with the new videos.
        pins.startLoad(HomeYouTubeLoadMode.OPEN)
        assertTrue(pins.mayShowCached("home", HomeYouTubeLoadMode.OPEN, visible = true))

        // A manual reload or an account change applies the fresh rows right away.
        pins.cachedShown("home", HomeYouTubeLoadMode.OPEN)
        pins.startLoad(HomeYouTubeLoadMode.EXPLICIT)
        assertTrue(pins.mayShowCached("home", HomeYouTubeLoadMode.EXPLICIT, visible = true))
        pins.cachedShown("home", HomeYouTubeLoadMode.EXPLICIT)
        assertTrue(pins.mayApplyFresh("home", visible = true))
    }

    private fun item(id: String) = YouTubeItem(videoId = id, title = id)
}
