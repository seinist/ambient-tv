package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ambient.tv.games.metadata.GameArtworkPriority
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h480dp-land-mdpi")
class GameArtworkPriorityPresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previousLoader: ImageLoader
    private lateinit var recordingLoader: ImageLoader
    private val requested = CopyOnWriteArrayList<String>()

    @Before fun installRecordingLoader() {
        rule.runOnUiThread {
            previousLoader = SingletonImageLoader.get(rule.activity)
            recordingLoader = ImageLoader.Builder(rule.activity).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        requested += chain.request.data.toString()
                        return ErrorResult(image = null, request = chain.request,
                            throwable = IllegalStateException("Offline game artwork fixture"))
                    }
                })
            }.build()
            SingletonImageLoader.setUnsafe(recordingLoader)
        }
    }

    @After fun restoreLoader() {
        rule.runOnUiThread {
            SingletonImageLoader.setUnsafe(previousLoader)
            recordingLoader.shutdown()
        }
    }

    @Test fun clippedComposedCardsWaitForVisibilityAndFocusedDemandMovesWithTheViewport() {
        val priority = GameArtworkPriority()
        var offset by mutableStateOf(0)
        var focused by mutableStateOf(1)
        var shown by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalGameArtworkPriority provides priority, LocalHandheldUi provides true) {
                AmbientTvTheme {
                    if (shown) Box(Modifier.size(160.dp, 80.dp).clipToBounds()) {
                        // All three stay composed, just like lazy precomposition neighbours.
                        // Only two fit the clipped parent, regardless of the outer window size.
                        repeat(3) { index ->
                            GameCoverArtwork(
                                cover = "https://example.invalid/game-$index.png",
                                title = "Game $index", systemId = "snes", fileId = "game-$index",
                                focused = focused == index,
                                modifier = Modifier.offset(x = (index * 80 + offset).dp).size(80.dp),
                            )
                        }
                    }
                }
            }
        }
        rule.waitUntil(5_000) { requested.size == 2 }
        rule.runOnIdle {
            assertEquals(listOf("game-1", "game-0"), priority.prioritizedFileIds())
            assertEquals(setOf("https://example.invalid/game-0.png", "https://example.invalid/game-1.png"), requested.toSet())
            offset = -80
            focused = 2
        }
        rule.waitUntil(5_000) { requested.size == 3 }
        rule.runOnIdle {
            assertEquals(listOf("game-2", "game-1"), priority.prioritizedFileIds())
            assertEquals("https://example.invalid/game-2.png", requested.last())
            shown = false
        }
        rule.runOnIdle { assertTrue("Leaving the view releases every demand owner", priority.prioritizedFileIds().isEmpty()) }
    }
}
