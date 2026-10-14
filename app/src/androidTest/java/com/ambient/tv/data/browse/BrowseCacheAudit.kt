package com.ambient.tv.data.browse

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ambient.tv.data.repository.PlexMediaCache
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.common.ContentRow
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.*

/** Two separate am instrument invocations verify persisted data across actual process restarts. */
internal class BrowseCacheAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    fun run(phase: String) = runBlocking {
        check(instrumentation.targetContext.packageName.let { it.endsWith(".browseaudit") || it.endsWith(".uiperfaudit") })
        BrowseWorkBudget.foreground = false
        val store = BrowseDiskStore(instrumentation.targetContext)
        val cache = PlexMediaCache()
        var namespace = "audit:account-a"
        cache.configurePersistence(store) { namespace }
        if (phase == "seed") {
            cache.clear()
            val items = (0 until 6_000).map { item("$it").copy(summary = "display data ".repeat(90)) }
            cache.withGeneration {
                cache.mergeAndRegister(items)
                cache.setSectionItems("library", items)
                cache.setOnDeck(items.take(5))
                cache.setChildren("series", items.take(10))
                Result.success(Unit)
            }.getOrThrow()
            withTimeout(20_000) {
                while (!store.read(namespace).keys.containsAll(listOf("section:library", "ondeck", "children:series"))) delay(100)
            }
            val raw = store.read(namespace, "section:library").values.single()
            check(raw.length > 2 * 1024 * 1024) { "Audit did not exceed CursorWindow size" }
            check(!raw.contains("private-token") && !raw.contains("stream-secret"))
            log("SEED: 6000 items persisted (${raw.length} chars), credentials and playback URLs excluded")
            return@runBlocking
        }
        val start = System.nanoTime()
        cache.restore()
        val restored = cache.sectionItems("library") ?: error("No persisted library after process restart")
        check(restored.size == 6_000)
        check(restored.all { it.mediaVersions.isEmpty() })
        check(cache.freshSectionItems("library", 60_000) == null)
        check(cache.children("series", Long.MAX_VALUE)?.size == 10)
        log("RESTORE: 6000 items after process restart, stale-but-readable in ${(System.nanoTime() - start) / 1_000_000}ms")
        cache.expire()
        check(cache.sectionItems("library")?.size == 6_000)
        cache.setSectionItems("library", emptyList())
        check(cache.sectionItems("library")!!.isEmpty())
        check(cache.mediaItem("5") != null) // Membership removal does not delete the title.
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldRequest = async {
            cache.withGeneration {
                started.complete(Unit); release.await()
                cache.setOnDeck(listOf(item("old-user")))
                Result.success(Unit)
            }
        }
        started.await()
        namespace = "audit:account-b"
        check(cache.onDeck() == null)
        release.complete(Unit)
        check(oldRequest.await().isFailure)
        check(cache.onDeck() == null)
        log("ISOLATION: source switch invalidates in-flight responses; empty completion clears membership")
        focusAudit()
    }

    private fun focusAudit() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UiCatalogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UiCatalogActivity
        val items = mutableStateOf((0 until 8).map { item("$it").copy(thumbUrl = null) })
        var focused: String? = null
        var clicked: String? = null
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitFocus(id: String) {
            repeat(100) {
                var ready = false
                main { ready = focused == id }
                if (ready) return
                Thread.sleep(40)
            }
            error("Expected focus $id, got $focused")
        }
        try {
            main {
                activity.setContent {
                    AmbientTvTheme {
                        Box(Modifier.fillMaxSize()) {
                            ContentRow(ContentRowData("Refresh", items.value, id = "stable-row"),
                                onItemClick = { clicked = it.ratingKey }, onItemFocused = { focused = it.ratingKey }, restoreRatingKey = "2")
                        }
                    }
                }
            }
            awaitFocus("2")
            main { items.value = listOf(item("new").copy(thumbUrl = null)) + items.value.map { if (it.ratingKey == "4") it.copy(title = "Updated") else it } }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(clicked == "2") }
            main { items.value = items.value.filterNot { it.ratingKey == "2" } }
            awaitFocus("3")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            main { check(clicked == "3") }
            log("D-PAD: insertion preserves the focused card; removal selects its surviving next neighbour")
        } finally { main { activity.finish() } }
    }

    private fun item(id: String) = MediaItem(
        ratingKey = id, title = "Title $id", type = "movie", index = null,
        thumbUrl = "https://example.invalid/thumb?X-Plex-Token=private-token", artUrl = null,
        year = 2026, summary = null, tagline = null, durationMs = 100_000, viewOffsetMs = 0,
        parentTitle = null, parentRatingKey = null, grandparentTitle = null, grandparentRatingKey = null,
        contentRating = null, rating = null, studio = null,
        mediaVersions = listOf(MediaVersion("https://example.invalid/play?token=stream-secret", "1080", "h264", "aac", "mp4")),
    )
}
