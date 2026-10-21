package com.ambient.tv.ui.common

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.ImageFetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import com.ambient.tv.ui.catalog.UiCatalogActivity
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/** Real Compose/Coil and D-pad checks, with independently gated artwork instead of network timing. */
class ProgressiveLoadingAudit(private val instrumentation: Instrumentation, private val log: (String) -> Unit) {
    private class Art(val color: Int) {
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val requests = AtomicInteger()
    }

    fun run() {
        check(instrumentation.targetContext.packageName.endsWith(".loadingaudit")) { "Use the isolated .loadingaudit app" }
        val context = instrumentation.targetContext
        val artwork = listOf(Art(Color.RED), Art(Color.GREEN), Art(Color.BLUE))
        val bounds = ConcurrentHashMap<Int, Rect>()
        val clicked = AtomicInteger(-1)
        val loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Art> {
                override fun create(data: Art, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                    data.requests.incrementAndGet()
                    data.started.countDown()
                    data.release.await()
                    ImageFetchResult(ColorDrawable(data.color).asImage(), false, DataSource.NETWORK)
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        val activity = instrumentation.startActivitySync(
            Intent(context, UiCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as UiCatalogActivity
        val first = FocusRequester()
        fun frame(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        fun sample(bitmap: Bitmap, index: Int): Int {
            val rect = checkNotNull(bounds[index])
            val root = checkNotNull(bounds[-1])
            // Shield screenshots use the physical 4K mode even when the app renders at 1080p.
            return bitmap.getPixel(
                (rect.center.x * bitmap.width / root.width).toInt(),
                (rect.center.y * bitmap.height / root.height).toInt(),
            )
        }
        fun capture(name: String): List<Int> = frame().let { bitmap ->
            File(context.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val colors = (0..3).map { sample(bitmap, it) }
            bitmap.recycle()
            colors
        }
        fun awaitColor(index: Int, expected: Int) {
            repeat(50) {
                val bitmap = frame()
                val actual = sample(bitmap, index)
                bitmap.recycle()
                if (actual == expected) return
                Thread.sleep(100)
            }
            capture("loading-failure")
            error("Artwork $index never appeared; bounds=$bounds")
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    AmbientTvTheme {
                        Column(Modifier.fillMaxSize().onGloballyPositioned { bounds[-1] = it.boundsInWindow() }.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Progressive loading audit", color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                artwork.forEachIndexed { index, art ->
                                    Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Available title ${index + 1}", color = androidx.tv.material3.MaterialTheme.colorScheme.onSurface)
                                        ArtworkImage(art, "Artwork ${index + 1}", Modifier.width(200.dp).height(110.dp)
                                            .onGloballyPositioned { bounds[index] = it.boundsInWindow() })
                                        TvActionButton("Open ${index + 1}", { clicked.set(index) },
                                            if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    }
                                }
                            }
                            SkeletonTile(Modifier.onGloballyPositioned { bounds[3] = it.boundsInWindow() })
                        }
                        LaunchedEffect(Unit) { first.requestFocus() }
                    }
                }
            }
            artwork.forEach { check(it.started.await(10, TimeUnit.SECONDS)) { "Image request never started" } }
            instrumentation.waitForIdleSync()
            Thread.sleep(350)
            val before = capture("loading-before-images")
            check(artwork.indices.all { before[it] != artwork[it].color })
            // Text is composed while every image is still pending; remote actions already work.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            check(clicked.get() == 0) { "Initial action not usable while images load" }
            log("PASS content actions usable before any artwork is loaded")
            artwork[0].release.complete(Unit)
            awaitColor(0, Color.RED)
            val partial = capture("loading-first-image")
            check(partial[1] != artwork[1].color && partial[2] != artwork[2].color) { "Pending slots resolved with the first image" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            check(clicked.get() == 1) { "D-pad failed on the still-loading second item" }
            log("PASS first image visible with two placeholders; D-pad reaches pending item")
            artwork[2].release.complete(Unit)
            awaitColor(2, Color.BLUE)
            val later = capture("loading-out-of-order")
            check(later[0] == Color.RED && later[1] != artwork[1].color)
            artwork[1].release.complete(Unit)
            awaitColor(1, Color.GREEN)
            check(capture("loading-complete").take(3) == artwork.map { it.color })
            check(artwork.all { it.requests.get() == 1 }) { "Measured image requests restarted" }
            log("PASS images appear independently out of order; visible image retained; one request per slot")
        } finally {
            artwork.forEach { it.release.complete(Unit) }
            instrumentation.runOnMainSync { activity.finish() }
            loader.shutdown()
        }
    }
}
