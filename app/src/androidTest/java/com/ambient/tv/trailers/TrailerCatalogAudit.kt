package com.ambient.tv.trailers

import android.app.Instrumentation
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Production catalog lifecycle against deterministic local HTTP fixtures, never a real API key. */
internal class TrailerCatalogAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { runCatching(action).onFailure { failure = it } }
        failure?.let { throw it }
    }
    fun run(activity: UiCatalogActivity) {
        val storage = instrumentation.targetContext.getSharedPreferences("audit-tmdb-settings", 0)
        val credentials = instrumentation.targetContext.getSharedPreferences("audit-tmdb-tokens", 0)
        val prefs = AppPreferences(storage).apply { trailersEnabled = true; trailerLanguage = "en" }
        val tokens = TokenStore(credentials, prefs).apply { tmdbApiKey = "local-fixture-only" }
        val started = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.url.encodedPath
            val body = if (request.url.encodedPath.endsWith("/videos")) {
                """{"results":[{"key":"abcdefghijk","name":"Fixture trailer","site":"YouTube","type":"Trailer","official":true}]}"""
            } else {
                val cinema = request.url.encodedPath.endsWith("/now_playing")
                if (cinema) { started.countDown(); Thread.sleep(800) }
                val title = if (cinema) "Cinema audit" else "Series audit"
                """{"page":1,"total_pages":1,"results":[{"id":7,"title":"$title","release_date":"2026-01-01"}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = TmdbTrailerRepository(tokens, client)
        val feed = mutableStateOf(TrailerFeed.CINEMA)
        try {
            main {
                activity.setContent {
                    AmbientTvTheme {
                        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            TrailerCatalogContent({}, {}, { _, _ -> }, feed.value.name, { feed.value = it }, repository, prefs)
                        }
                    }
                }
            }
            check(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            main { feed.value = TrailerFeed.SERIES }
            Thread.sleep(200)
            main { feed.value = TrailerFeed.CINEMA }
            val deadline = SystemClock.uptimeMillis() + 7000
            // Compose virtual nodes are present in the tree, but platform text-search can omit them.
            fun containsTitle(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): Boolean {
                if (node == null || depth > 15) return false
                return node.text?.toString()?.contains("Cinema audit") == true ||
                    node.contentDescription?.toString()?.contains("Cinema audit") == true ||
                    (0 until node.childCount).any { containsTitle(node.getChild(it), depth + 1) }
            }
            var found = false
            while (!found && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100)
                found = containsTitle(instrumentation.uiAutomation.rootInActiveWindow)
            }
            if (!found) {
                fun describe(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): List<String> {
                    if (node == null || depth > 15) return emptyList()
                    return listOf("${node.text}/${node.contentDescription}") + (0 until node.childCount).flatMap { describe(node.getChild(it), depth + 1) }
                }
                log("TMDB diagnostic requests=$requests nodes=${describe(instrumentation.uiAutomation.rootInActiveWindow)}")
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    java.io.File(instrumentation.targetContext.filesDir, "audit-tmdb-failure.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
            }
            check(found) { "Returning to a cancelled TMDB tab left its loading state stuck" }
            log("PASS production TMDB catalog recovers after cancelling a tab load (local HTTP fixture; no live TMDB service claim)")
        } finally {
            main { activity.setContent {} }
            Thread.sleep(300)
            storage.edit().clear().commit()
            credentials.edit().clear().commit()
        }
    }
}
