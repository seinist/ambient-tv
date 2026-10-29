package com.ambient.tv.ui.youtube

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    private fun mount(): Fixture {
        val fixture = Fixture()
        rule.setContent {
            CompositionLocalProvider(
                LocalPlayerChromeState provides fixture.chrome,
                LocalPlayerPanelNavigation provides fixture.chrome.panels,
                LocalHandheldUi provides false,
            ) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        fixture.model.value.Content(visible = true, panel = UnifiedPlayerPanel.NONE)
                    }
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun restartButton() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.detail_restart))
    private fun nextButton() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.player_next_item))

    @Test fun navigationButtonsStayVisibleAndCallOnlyAvailableActionsForEveryRendererLabel() {
        val fixture = mount()
        restartButton().assertIsDisplayed().performClick()
        nextButton().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.restarts)
            assertEquals(0, fixture.advances)
        }

        for ((index, backend) in listOf("Media3", "VLC", "MPV").withIndex()) {
            rule.runOnIdle {
                fixture.model.value = fixture.model.value.copy(backend = backend,
                    itemControls = PlaybackItemControls(restart = { fixture.restarts++ }, next = { fixture.advances++ }))
            }
            restartButton().assertIsDisplayed().performClick()
            nextButton().assertIsDisplayed().performClick()
            rule.runOnIdle {
                assertEquals(index + 2, fixture.restarts)
                assertEquals(index + 1, fixture.advances)
            }
        }

        rule.runOnIdle { fixture.model.value = fixture.model.value.copy(itemControls = PlaybackItemControls()) }
        // Disabled TV controls deliberately remain focusable; clicks must have no effect.
        restartButton().assertIsDisplayed().performClick()
        nextButton().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(4, fixture.restarts)
            assertEquals(3, fixture.advances)
        }
    }

    @Test fun osdSettingHidesBothNavigationButtonsAndStillOffersTheSettingToRestoreThem() {
        mount()
        restartButton().assertIsDisplayed()
        nextButton().assertIsDisplayed()
        rule.runOnIdle {
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.YOUTUBE, OsdButton.NEXT, false)
        }
        restartButton().assertDoesNotExist()
        nextButton().assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(OsdButton.NEXT in OsdPlayer.YOUTUBE.offeredButtons())
            setOsdButtonVisible(videoImagePreferences(rule.activity), OsdPlayer.YOUTUBE, OsdButton.NEXT, true)
        }
        restartButton().assertIsDisplayed()
        nextButton().assertIsDisplayed()
    }

    @Test fun contentWithoutVideoItemControlsDoesNotAdvertiseNavigationButtons() {
        val fixture = mount()
        rule.runOnIdle { fixture.model.value = fixture.model.value.copy(itemControls = null) }
        restartButton().assertDoesNotExist()
        nextButton().assertDoesNotExist()
        rule.runOnIdle {
            fixture.model.value = fixture.model.value.copy(playback = fixture.model.value.playback.copy(isLive = true))
        }
        restartButton().assertDoesNotExist()
        nextButton().assertDoesNotExist()
    }
}
