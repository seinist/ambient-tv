package com.ambient.tv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real card rendering and input, with a recording image loader that never contacts a server. */
@OptIn(DelicateCoilApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackSeekStopTest.PreferencesApplication::class,
    qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinimalNextUpOverlayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previousImageLoader: ImageLoader
    private lateinit var testImageLoader: ImageLoader
    private val imageRequests = AtomicInteger()

    private class Fixture {
        val candidate = mutableStateOf(NextUpCandidate(
            mediaId = "next", title = "The Long Way Home", subtitle = "Example Series",
            source = "plex", mediaType = "episode", season = 2, episode = 4,
            artworkUrl = "https://example.invalid/next-up.jpg", posterUrl = "https://example.invalid/poster.jpg",
            durationMs = 2_880_000, contentRating = "DE/12",
        ))
        val autoplay = mutableStateOf(true)
        val confirmation = mutableStateOf(false)
        val seconds = mutableStateOf(12)
        val claimFocus = mutableStateOf(true)
        val interactive = mutableStateOf(true)
        val underlyingFocus = FocusRequester()
        var plays = 0
        var closes = 0
        var skips = 0
        var autoplayChanges = 0
    }

    @Before fun recordArtworkRequests() {
        rule.runOnUiThread {
            previousImageLoader = SingletonImageLoader.get(rule.activity)
            testImageLoader = ImageLoader.Builder(rule.activity).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        imageRequests.incrementAndGet()
                        return ErrorResult(image = null, request = chain.request,
                            throwable = IllegalStateException("Offline NextUp fixture"))
                    }
                })
            }.build()
            SingletonImageLoader.setUnsafe(testImageLoader)
        }
    }

    @After fun restoreArtworkLoader() {
        rule.runOnUiThread {
            SingletonImageLoader.setUnsafe(previousImageLoader)
            testImageLoader.shutdown()
        }
    }

    private fun mount(
        handheld: Boolean = true,
        minimal: Boolean = true,
        fixture: Fixture = Fixture(),
    ): Fixture {
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(48.dp).testTag("underlying-control")
                            .focusRequester(fixture.underlyingFocus).focusable())
                        NextUpOverlay(
                            candidate = fixture.candidate.value,
                            secondsRemaining = fixture.seconds.value,
                            leadSeconds = 30,
                            autoplay = fixture.autoplay.value,
                            stillWatching = fixture.confirmation.value,
                            onPlayNow = { fixture.plays++ },
                            onDismiss = { fixture.closes++ },
                            onSkipCredits = { fixture.skips++ },
                            onAutoplayChanged = { fixture.autoplayChanges++ },
                            modifier = Modifier.testTag("next-up-card"),
                            claimFocus = fixture.claimFocus.value,
                            minimal = minimal,
                            interactive = fixture.interactive.value,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        return fixture
    }

    private fun action(id: Int) = rule.onNode(hasText(rule.activity.getString(id)) and hasClickAction())
    private fun label(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    @Test fun minimalCardSkipsArtworkAndSecondaryControlsEvenWhenTheirDataAndCallbacksExist() {
        val fixture = mount()
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
        rule.onNodeWithText("S02 E04", substring = true).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.next_up_starts_in_short, 12)).assertIsDisplayed()
        rule.onNodeWithContentDescription(fixture.candidate.value.title).assertDoesNotExist()
        rule.onNodeWithText("FSK 12", substring = true).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.next_up_autoplay_on)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.player_skip_credits)).assertDoesNotExist()
        action(R.string.player_play).performClick()
        action(R.string.next_up_close).performClick()
        rule.runOnIdle {
            assertEquals(0, imageRequests.get())
            assertEquals(1, fixture.plays)
            assertEquals(1, fixture.closes)
            assertEquals(0, fixture.skips)
            assertEquals(0, fixture.autoplayChanges)
        }
    }

    @Test fun disabledAutoplayAndConfirmationNeverImplyAnAutomaticStart() {
        val fixture = mount(fixture = Fixture().apply { autoplay.value = false })
        rule.onNodeWithText(label(R.string.next_up_autoplay_off_short)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.next_up_starts_in_short, 12)).assertDoesNotExist()
        rule.runOnIdle { fixture.autoplay.value = true; fixture.confirmation.value = true }
        rule.onNodeWithText(label(R.string.next_up_still_watching)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.next_up_starts_in_short, 12)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.next_up_confirm)).assertDoesNotExist()
        action(R.string.next_up_continue).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, fixture.plays) }
    }

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-port-mdpi")
    fun narrowPhoneKeepsBothActionsReadableAndTouchableWithATwoLineTitle() {
        val fixture = mount(fixture = Fixture().apply {
            candidate.value = candidate.value.copy(
                title = "A very long next episode title that needs multiple lines on a narrow phone display",
            )
        })
        val card = rule.onNodeWithTag("next-up-card").fetchSemanticsNode().boundsInRoot
        assertTrue("The card must respect the phone viewport", card.width <= 320f + 1f)
        val actions = listOf(action(R.string.player_play), action(R.string.next_up_close))
        for (action in actions) {
            val bounds = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Touch height must remain usable", bounds.height >= 48f)
            assertTrue("Action must fit in the card", bounds.left >= card.left && bounds.right <= card.right)
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(fixture.candidate.value.title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertTrue("The title must remain compact", titleLayouts.single().lineCount in 1..2)
        for (id in listOf(R.string.player_play, R.string.next_up_close)) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label(id), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val lineBounds = (0 until layout.lineCount).map { layout.getLineLeft(it) to layout.getLineRight(it) }
            val description = "Action '${label(id)}': size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
                "constraints=${layout.layoutInput.constraints}, lines=$lineBounds"
            assertFalse("$description must not clip vertically", layout.didOverflowHeight)
            // The cached paragraph retains its available width while Text wraps to the glyphs.
            // Check actual line extents, allowing at most one physical pixel of integer rounding.
            assertTrue("$description must not clip horizontally",
                lineBounds.all { (left, right) -> left >= -1f && right <= layout.size.width + 1f })
            assertTrue("$description must not truncate its label",
                (0 until layout.lineCount).none(layout::isLineEllipsized))
            assertEquals("$description must show every character", label(id).length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
    }

    @Test fun passiveCardKeepsExistingTvFocusThenClaimsPrimaryWithoutResettingSecondaryFocus() {
        val fixture = mount(handheld = false, fixture = Fixture().apply { claimFocus.value = false })
        rule.runOnIdle { fixture.underlyingFocus.requestFocus(); fixture.seconds.value = 11 }
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag("underlying-control").assertIsFocused()
        action(R.string.player_play).assertIsNotFocused()
        rule.runOnIdle { fixture.claimFocus.value = true }
        // Flush the state change so the focus effect starts before advancing its delayed frame.
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(200L)
        rule.waitForIdle()
        action(R.string.player_play).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        action(R.string.next_up_close).assertIsFocused()
        rule.runOnIdle { fixture.seconds.value = 10 }
        rule.mainClock.advanceTimeBy(200L)
        action(R.string.next_up_close).assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(1, fixture.closes); assertEquals(0, fixture.plays) }
    }

    @Test fun outgoingCardReleasesFocusAndRejectsEvenPreviouslyCapturedActivation() {
        val fixture = mount(handheld = false)
        rule.mainClock.advanceTimeBy(200L)
        action(R.string.player_play).assertIsFocused()
        val play = action(R.string.player_play).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        val close = action(R.string.next_up_close).fetchSemanticsNode().config[SemanticsActions.OnClick].action
        rule.runOnIdle { fixture.interactive.value = false }
        rule.runOnIdle { fixture.underlyingFocus.requestFocus() }
        rule.mainClock.advanceTimeBy(200L)
        action(R.string.player_play).assertIsNotEnabled().assertIsNotFocused()
        action(R.string.next_up_close).assertIsNotEnabled()
        rule.onNodeWithTag("underlying-control").assertIsFocused()
        rule.runOnIdle {
            play?.invoke()
            close?.invoke()
            assertEquals(0, fixture.plays)
            assertEquals(0, fixture.closes)
        }
    }

    @Test fun classicLayoutKeepsItsArtworkAndAutoplayAndCreditsActions() {
        mount(minimal = false)
        rule.onNodeWithText(label(R.string.next_up_autoplay_on)).assertIsDisplayed()
        rule.onNodeWithText(label(R.string.player_skip_credits)).assertIsDisplayed()
        action(R.string.next_up_play_now).assertIsDisplayed()
        rule.onNodeWithText("FSK 12", substring = true).assertIsDisplayed()
        rule.waitUntil(timeoutMillis = 2_000) { imageRequests.get() > 0 }
    }
}
