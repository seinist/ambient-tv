package com.ambient.tv.trailers

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the production lazy rows and leaf focus, including navigation's saved viewport. */
@OptIn(DelicateCoilApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class TrailerTitleFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previousImageLoader: ImageLoader
    private lateinit var testImageLoader: ImageLoader

    private class Fixture(initialVideos: List<TrailerVideo>, initialKey: String?) {
        var videos by mutableStateOf(initialVideos)
        var serverTrailers by mutableStateOf(emptyList<MediaItem>())
        var loading by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var focusKey by mutableStateOf(initialKey)
        var focusRequest by mutableIntStateOf(1)
        var focusedKey: String? = null
        var launchedVideo: String? = null
    }

    @Before fun setUpArtwork() {
        rule.runOnUiThread {
            previousImageLoader = SingletonImageLoader.get(rule.activity)
            testImageLoader = ImageLoader.Builder(rule.activity).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult = ErrorResult(
                        image = null,
                        request = chain.request,
                        throwable = IllegalStateException("Trailer focus fixture does not fetch artwork"),
                    )
                })
            }.build()
            SingletonImageLoader.setUnsafe(testImageLoader)
        }
    }

    @After fun restoreArtwork() {
        rule.runOnUiThread {
            SingletonImageLoader.setUnsafe(previousImageLoader)
            testImageLoader.shutdown()
        }
    }

    private fun mount(fixture: Fixture) {
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalPerformanceProfile provides PerformanceProfile.resolve(PerformanceMode.LOW, lowEndDevice = false),
            ) {
                AmbientTvTheme {
                    val savedState = rememberSaveableStateHolder()
                    // The second row is below the fold, so returning to it must also scroll
                    // the outer column instead of merely selecting an inner-row item.
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        if (fixture.visible) savedState.SaveableStateProvider("title") {
                            TrailerTitleContent(
                                title = "Film",
                                videos = fixture.videos,
                                serverTrailers = fixture.serverTrailers,
                                configured = true,
                                loading = fixture.loading,
                                error = null,
                                focusKey = fixture.focusKey,
                                focusRequest = fixture.focusRequest,
                                onBack = {}, onOpenSettings = {}, onRetry = {},
                                onPlay = {
                                    fixture.launchedVideo = it
                                    fixture.visible = false
                                },
                                onPlayServerTrailer = {},
                                onFocused = { fixture.focusedKey = it },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun leavingPlaybackRestoresTheClickedNonfirstVideoWithItsSavedViewport() {
        val fixture = Fixture(videos(), key(8))
        mount(fixture)
        focused(fixture, 8)
        tile(8).performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(video(8).key, fixture.launchedVideo)
            fixture.visible = true
        }
        focused(fixture, 8)
    }

    @Test fun nextAndPreviousReturnsReplaceAnOldViewportEvenWithoutRemounting() {
        val fixture = Fixture(videos(), key(2))
        mount(fixture)
        focused(fixture, 2)

        // Next may have advanced beyond anything composed in the original viewport.
        returnTo(fixture, 27)
        focused(fixture, 27)
        returnTo(fixture, 26)
        focused(fixture, 26)
        returnTo(fixture, 1)
        focused(fixture, 1)
    }

    @Test fun returnUsesVideoIdentityAfterTheTitleListHasBeenReordered() {
        val fixture = Fixture(videos(), key(4))
        mount(fixture)
        focused(fixture, 4)
        tile(4).performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            fixture.videos = fixture.videos.reversed()
            fixture.focusKey = key(7)
            fixture.focusRequest++
            fixture.visible = true
        }
        focused(fixture, 7)
    }

    @Test fun tmdbReturnReachesItsOwnRowWhenServerTrailersAreAboveIt() {
        val fixture = Fixture(videos(), key(18)).apply {
            serverTrailers = listOf(serverTrailer(0), serverTrailer(1))
        }
        mount(fixture)
        focused(fixture, 18)
        returnTo(fixture, 17)
        focused(fixture, 17)
    }

    @Test fun refreshDoesNotReapplyAnAlreadyConsumedReturnAfterUserNavigation() {
        val fixture = Fixture(videos(), key(5))
        mount(fixture)
        focused(fixture, 5)
        tile(5).performKeyInput { pressKey(Key.DirectionRight) }
        focused(fixture, 6)

        // Inserting an earlier video changes the old target's index. It is still the same
        // consumed focus request and must not pull the user back from the sixth video.
        rule.runOnIdle { fixture.videos = listOf(video(90)) + fixture.videos }
        focused(fixture, 6)
    }

    @Test fun returnWaitsForVideosInsteadOfClaimingTheFirstLoadedTile() {
        val fixture = Fixture(emptyList(), key(21)).apply { loading = true }
        mount(fixture)
        rule.runOnIdle {
            assertNull(fixture.focusedKey)
            fixture.videos = videos()
            fixture.loading = false
        }
        focused(fixture, 21)
    }

    @Test fun aRemovedReturnTargetFallsBackToAPlayableVideo() {
        val fixture = Fixture(videos(), key(99))
        mount(fixture)
        focused(fixture, 0)
    }

    private fun returnTo(fixture: Fixture, index: Int) {
        rule.runOnIdle {
            fixture.focusKey = key(index)
            fixture.focusRequest++
        }
    }

    private fun focused(fixture: Fixture, index: Int) {
        rule.waitForIdle()
        tile(index).assertIsDisplayed().assertIsFocused()
        rule.runOnIdle { assertEquals(key(index), fixture.focusedKey) }
    }

    private fun tile(index: Int) = rule.onNode(
        (hasText(video(index).name) or hasContentDescription(video(index).name)) and hasClickAction(),
    )

    private fun key(index: Int) = "trailer-video:${video(index).key}"
    private fun videos() = List(36, ::video)
    private fun video(index: Int) = TrailerVideo(
        key = "video-${index.toString().padStart(5, '0')}",
        name = "Trailer $index", site = "YouTube", type = "Trailer", official = true,
    )

    private fun serverTrailer(index: Int) = MediaItem(
        ratingKey = "server-trailer-$index", title = "Server trailer $index", type = "clip", index = null,
        thumbUrl = null, artUrl = null, year = null, summary = null, tagline = null,
        durationMs = null, viewOffsetMs = null, parentTitle = null, parentRatingKey = null,
        grandparentTitle = null, grandparentRatingKey = null, contentRating = null, rating = null, studio = null,
    )
}
