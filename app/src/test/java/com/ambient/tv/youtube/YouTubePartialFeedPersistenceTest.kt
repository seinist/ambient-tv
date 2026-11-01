package com.ambient.tv.youtube

import com.ambient.tv.data.browse.BrowseDiskStore
import com.ambient.tv.data.browse.BrowseWorkBudget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class YouTubePartialFeedPersistenceTest {
    @After fun resetBudget() {
        BrowseWorkBudget.foreground = false
        BrowseWorkBudget.musicPlaying = false
    }

    @Test fun partialFeedIsKeptWithoutDeletingEntriesAndReachesDisk() = runBlocking {
        val disk = BrowseDiskStore(RuntimeEnvironment.getApplication())
        val cache = YouTubeContentCache(disk) { "account-a" }
        cache.restore()
        val revision = cache.revision()
        cache.replace("feed:home", listOf(
            YouTubeRow("Empfohlen", listOf(item("a"), item("b"))),
            YouTubeRow("Musik", listOf(item("m"))),
        ), revision)

        // SmartTube stopped after 16 emissions: only the first row arrived, with new videos.
        val kept = cache.persistPartial("feed:home", listOf(YouTubeRow("Empfohlen", listOf(item("x"), item("a")))), revision)

        assertEquals(listOf("Empfohlen", "Musik"), kept.map { it.id })
        assertEquals(listOf("a", "b", "x"), kept.first().items.map { it.videoId })
        awaitDisk(disk, "account-a") { "\"x\"" in it && "\"m\"" in it }

        // Next app start: a fresh cache instance restores the partial display state.
        val restarted = YouTubeContentCache(disk) { "account-a" }
        restarted.restore()
        val restored = restarted.get("feed:home").orEmpty()
        assertEquals(listOf("Empfohlen", "Musik"), restored.map { it.id })
        assertEquals(setOf("a", "b", "x"), restored.first().items.map { it.videoId }.toSet())
    }

    @Test fun stopFlushesSnapshotsThatPlaybackDeferred() = runBlocking {
        val disk = BrowseDiskStore(RuntimeEnvironment.getApplication())
        BrowseWorkBudget.foreground = true
        BrowseWorkBudget.musicPlaying = true
        disk.put("scope", "rows:feed:home", { true }) { "[\"deferred\"]" }
        Thread.sleep(400)
        assertTrue(disk.read("scope").isEmpty())

        // MainActivity.onStop: leave the foreground, then flush.
        BrowseWorkBudget.foreground = false
        disk.flush()
        awaitDisk(disk, "scope") { "deferred" in it }
    }

    private suspend fun awaitDisk(disk: BrowseDiskStore, scope: String, matches: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (disk.read(scope)["rows:feed:home"]?.let(matches) == true) return
            Thread.sleep(25)
        }
        throw AssertionError("snapshot did not reach disk")
    }

    private fun item(id: String) = YouTubeItem(videoId = id, title = id)
}
