package com.ambient.tv.ui.youtube

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ambient.tv.R
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.player.LocalPlayerChromeState
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.OsdPlayer
import com.ambient.tv.ui.player.PlaybackItemControls
import com.ambient.tv.ui.player.PlaybackSeekStopTest
import com.ambient.tv.ui.player.PlayerChromeState
import com.ambient.tv.ui.player.offeredButtons
import com.ambient.tv.ui.player.setOsdButtonVisible
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.youtube.YouTubePlayback
import com.ambient.tv.youtube.YouTubeMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land", application = PlaybackSeekStopTest.PreferencesApplication::class)
class YouTubeOsdNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previousImageLoader: ImageLoader
    private lateinit var testImageLoader: ImageLoader

    private class Fixture {
        val chrome = PlayerChromeState().apply { osd.expand() }
        var restarts = 0
        var previousVideos = 0
        var advances = 0
        val model = mutableStateOf(YouTubeOsdModel(
            playback = YouTubePlayback(videoId = "fixture", title = "Video", uri = "fixture://video"),
            backend = "Media3", position = 30_000, duration = 0,
            playing = true, loading = false, seekJumpMs = 10_000,
            onToggle = {}, onSeek = {}, onBack = {}, info = emptyList(),
            itemControls = PlaybackItemControls(restart = { restarts++ }),
        ))
    }

    @Before fun setUp() {
        rule.runOnUiThread {
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.YOUTUBE, OsdButton.NEXT, true)
            previousImageLoader = SingletonImageLoader.get(rule.activity)
            // The real OSD includes thumbnail artwork. Keep this navigation fixture offline.
            testImageLoader = ImageLoader.Builder(rule.activity).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult = ErrorResult(
                        image = null,
                        request = chain.request,
                        throwable = IllegalStateException("No artwork required for the OSD navigation fixture"),
                    )
                })
            }.build()
            SingletonImageLoader.setUnsafe(testImageLoader)
        }
    }

    @After fun tearDown() {
        rule.runOnUiThread {
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.YOUTUBE, OsdButton.NEXT, true)
            SingletonImageLoader.setUnsafe(previousImageLoader)
            testImageLoader.shutdown()
        }
    }

    private fun mount(playerState: StateFlow<YouTubePlayerState>? = null): Fixture {
        val fixture = Fixture()
        rule.setContent {
            CompositionLocalProvider(
                LocalPlayerChromeState provides fixture.chrome,
                LocalPlayerPanelNavigation provides fixture.chrome.panels,
                LocalHandheldUi provides false,
            ) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        val playback = playerState?.collectAsState()?.value?.playback
                        val model = fixture.model.value
                        (playback?.let { model.copy(playback = it) } ?: model)
                            .Content(visible = true, panel = UnifiedPlayerPanel.NONE)
                    }
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun restartButton() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.detail_restart))
    private fun previousButton() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.player_previous_item))
    private fun nextButton() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.player_next_item))

    @Test fun lateTrailerMetadataReplacesThePlaceholderInTheVisibleOsd() {
        val playback = YouTubePlayback(
            videoId = "tmdb-trailer", title = "SmartTube", uri = "fixture://trailer",
            resumeMs = 0L, resumeResolved = true,
        )
        val state = MutableStateFlow(YouTubePlayerState(loading = false, playback = playback))
        mount(state)
        // SmartTube is also a source badge, so it legitimately remains elsewhere in the OSD.
        rule.onAllNodesWithText("SmartTube", useUnmergedTree = true)[0].assertIsDisplayed()
        rule.onNodeWithText("Official Movie Trailer", useUnmergedTree = true).assertDoesNotExist()

        runBlocking {
            updateYouTubePlayerMetadata(
                videoId = playback.videoId,
                isTrailer = true,
                state = state,
                loadMetadata = {
                    YouTubeMetadata(
                        videoId = playback.videoId, title = "Official Movie Trailer",
                        author = "Movie Studio", publishedDate = "vor 3 Tagen",
                        durationMs = 120_000L, percentWatched = 80,
                    )
                },
                loadChannel = { error("Trailers do not load account controls") },
            )
        }

        // The offline artwork fixture shows the title in its fallback tile as well as
        // the real header. Both must update and remain visible after enrichment.
        val titles = rule.onAllNodesWithText("Official Movie Trailer", useUnmergedTree = true)
        titles.assertCountEquals(2)
        titles[0].assertIsDisplayed()
        titles[1].assertIsDisplayed()
        rule.onNodeWithText("Movie Studio", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("· vor 3 Tagen", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(playback.uri, state.value.playback?.uri)
        assertEquals(0L, state.value.playback?.resumeMs)
    }

    @Test fun publicationShowsBesideChannelAndDisappearsForRunningLiveStreams() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(playback = fixture.model.value.playback.copy(
                author = "Fixture Channel", publishedDate = "vor 3 Tagen",
            ))
        }
        rule.onNodeWithText("Fixture Channel", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("· vor 3 Tagen", useUnmergedTree = true).assertIsDisplayed()

        // Without a channel, the date stays below the title without a leading separator.
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(playback = fixture.model.value.playback.copy(author = null))
        }
        rule.onNodeWithText("vor 3 Tagen", useUnmergedTree = true).assertIsDisplayed()

        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(playback = fixture.model.value.playback.copy(isLive = true))
        }
        rule.onNodeWithText("· vor 3 Tagen", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("vor 3 Tagen", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun navigationButtonsStayVisibleAndCallOnlyAvailableActionsForEveryRendererLabel() {
        val fixture = mount()
        previousButton().assertIsDisplayed().performClick()
        restartButton().assertDoesNotExist()
        nextButton().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(0, fixture.restarts)
            assertEquals(0, fixture.previousVideos)
            assertEquals(0, fixture.advances)
        }

        for ((index, backend) in listOf("Media3", "VLC", "MPV").withIndex()) {
            rule.runOnIdle {
                fixture.model.value = fixture.model.value.copy(backend = backend,
                    itemControls = PlaybackItemControls(
                        restart = { fixture.restarts++ }, next = { fixture.advances++ },
                        previous = { fixture.previousVideos++ }, hasPreviousItem = true,
                    ))
            }
            previousButton().assertIsDisplayed().performClick()
            nextButton().assertIsDisplayed().performClick()
            rule.runOnIdle {
                assertEquals(0, fixture.restarts)
                assertEquals(index + 1, fixture.previousVideos)
                assertEquals(index + 1, fixture.advances)
            }
        }

        rule.runOnIdle { fixture.model.value = fixture.model.value.copy(itemControls = PlaybackItemControls()) }
        // Disabled TV controls deliberately remain focusable; clicks must have no effect.
        previousButton().assertIsDisplayed().performClick()
        nextButton().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(0, fixture.restarts)
            assertEquals(3, fixture.previousVideos)
            assertEquals(3, fixture.advances)
        }
    }

    @Test fun osdSettingHidesBothNavigationButtonsAndStillOffersTheSettingToRestoreThem() {
        mount()
        previousButton().assertIsDisplayed()
        nextButton().assertIsDisplayed()
        rule.runOnIdle {
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.YOUTUBE, OsdButton.NEXT, false)
        }
        previousButton().assertDoesNotExist()
        nextButton().assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(OsdButton.NEXT in OsdPlayer.YOUTUBE.offeredButtons())
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.YOUTUBE, OsdButton.NEXT, true)
        }
        previousButton().assertIsDisplayed()
        nextButton().assertIsDisplayed()
    }

    @Test fun historyPreviousNeverFallsBackToRestartWhileBlockedOrAtTheFirstVideo() {
        val fixture = mount()
        for ((index, backend) in listOf("Media3", "VLC", "MPV").withIndex()) {
            rule.runOnIdle {
                fixture.model.value = fixture.model.value.copy(backend = backend, itemControls = PlaybackItemControls(
                    restart = { fixture.restarts++ },
                    previous = { fixture.previousVideos++ },
                    hasPreviousItem = true,
                ))
            }
            restartButton().assertDoesNotExist()
            previousButton().assertIsDisplayed().performClick()
            rule.runOnIdle {
                assertEquals(index + 1, fixture.previousVideos)
                assertEquals(0, fixture.restarts)
            }
        }

        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(itemControls = PlaybackItemControls(
                restart = { fixture.restarts++ }, previous = null, hasPreviousItem = true,
            ))
        }
        previousButton().assertIsDisplayed().performClick()
        restartButton().assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(3, fixture.previousVideos)
            assertEquals(0, fixture.restarts)
            fixture.model.value = fixture.model.value.copy(itemControls = PlaybackItemControls(restart = { fixture.restarts++ }))
        }
        previousButton().assertIsDisplayed().performClick()
        restartButton().assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, fixture.restarts)
            assertEquals(3, fixture.previousVideos)
        }
    }

    @Test fun seekbarUsesFullMovingDvrWindowAndShowsZeroAtOldestBufferedFrame() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(
                playback = fixture.model.value.playback.copy(
                    isLive = true, durationMs = 850_000L, resumeMs = 850_000L,
                ),
                position = 0L,
                duration = 850_000L, // stale metadata must never become the OSD's limit
                liveDvrWindow = YouTubeLiveDvrWindow(livePositionMs = 110_000L, windowEndMs = 120_000L),
            )
        }
        rule.onNodeWithText("0:00 / 2:00", useUnmergedTree = true).assertIsDisplayed()

        // Updating the manifest length must update the right edge, even when its safe
        // default position (the actual playback point) remains behind it.
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(
                position = 85_000L,
                liveDvrWindow = YouTubeLiveDvrWindow(livePositionMs = 130_000L, windowEndMs = 145_000L),
            )
        }
        rule.onNodeWithText("1:25 / 2:25", useUnmergedTree = true).assertIsDisplayed()

        // An absolute decoder timeline must show its seekable subrange, not the
        // entire elapsed broadcast or a saved watch position.
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(
                position = 10_000_000L,
                liveDvrWindow = YouTubeLiveDvrWindow(
                    livePositionMs = 10_085_000L, windowEndMs = 10_090_000L,
                    windowStartMs = 10_000_000L,
                ),
            )
        }
        rule.onNodeWithText("0:00 / 1:30", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun seekableLiveDvrShowsGoLiveActionAndRewindControls() {
        val fixture = mount()
        var goLiveRequests = 0
        val seekDeltas = mutableListOf<Long>()
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(
                playback = fixture.model.value.playback.copy(isLive = true),
                position = 45_000L,
                duration = 120_000L,
                liveDvrWindow = YouTubeLiveDvrWindow(120_000L),
                onGoLive = { goLiveRequests++ },
                onSeek = { seekDeltas += it },
            )
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.timeshift_go_live))
            .assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, goLiveRequests) }
        // The rewind buttons are gated by actual DVR support, not by !isLive.
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.player_seek_back,
            (com.ambient.tv.ui.player.BackgroundPlaybackSession.seekJumpMs(10_000L) / 1_000L).toInt().coerceAtLeast(1)))
            .assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(seekDeltas.any { it < 0L }) }
    }

    @Test fun ordinaryLiveStreamNeverExposesFalseSeekingOrGoLiveAction() {
        val fixture = mount()
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(
                playback = fixture.model.value.playback.copy(isLive = true),
                position = 45_000L,
                duration = 120_000L,
                liveDvrWindow = null,
                onGoLive = {},
            )
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.timeshift_go_live))
            .assertDoesNotExist()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.player_seek_back,
            (com.ambient.tv.ui.player.BackgroundPlaybackSession.seekJumpMs(10_000L) / 1_000L).toInt().coerceAtLeast(1)))
            .assertDoesNotExist()
    }

    @Test fun contentWithoutVideoItemControlsDoesNotAdvertiseNavigationButtons() {
        val fixture = mount()
        rule.runOnIdle { fixture.model.value = fixture.model.value.copy(itemControls = null) }
        restartButton().assertDoesNotExist()
        previousButton().assertDoesNotExist()
        nextButton().assertDoesNotExist()
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(playback = fixture.model.value.playback.copy(isLive = true))
        }
        restartButton().assertDoesNotExist()
        previousButton().assertDoesNotExist()
        nextButton().assertDoesNotExist()
    }
}
