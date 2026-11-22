package com.ambient.tv.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.LocalPlayerPanelNavigation
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class PlayerTimelineStartupTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(compact: Boolean) {
        val duration = mutableLongStateOf(0L)
        val extraLine = mutableStateOf(false)
        val chrome = PlayerChromeState().apply { if (!compact) osd.expand() }
        val actionFocus = FocusRequester()
        val actionOwner = Any()
        val seeks = mutableListOf<Long>()
        var interactions = 0
    }

    private fun mount(handheld: Boolean = false, withFocus: Boolean = false, compact: Boolean = false): Fixture {
        val fixture = Fixture(compact)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides handheld,
                LocalPlayerChromeState provides fixture.chrome.takeIf { withFocus },
                LocalPlayerControlFocus provides fixture.chrome.focus.takeIf { withFocus },
                LocalPlayerPanelNavigation provides fixture.chrome.panels.takeIf { withFocus },
            ) {
                AmbientTvTheme {
                    if (withFocus) {
                        RequestPlayerOverlayFocus(fixture.actionFocus, enabled = true, playbackStarted = true)
                    }
                    Box(Modifier.fillMaxSize()) {
                        ReferencePlayerBottomContent {
                            BasicText("Playing video", Modifier.testTag("header"))
                            PlayerTimelineSlot(Modifier.testTag("timeline-slot")) {
                                Column {
                                    if (fixture.extraLine.value) {
                                        BasicText("Late chapter", Modifier.height(24.dp))
                                    }
                                    TvPlayerSeekBar(
                                        positionMs = 30_000L,
                                        durationMs = fixture.duration.longValue,
                                        onSeekBy = { fixture.seeks += it },
                                        onInteraction = { fixture.interactions++ },
                                        showEndTime = false,
                                        modifier = Modifier.testTag("timeline"),
                                    )
                                }
                            }
                            if (withFocus) {
                                DisposableEffect(fixture.actionOwner) {
                                    fixture.chrome.focus.register("settings", fixture.actionFocus, fixture.actionOwner)
                                    onDispose { fixture.chrome.focus.unregister("settings", fixture.actionOwner) }
                                }
                                BasicText("Settings", Modifier
                                    .testTag("settings")
                                    .height(32.dp)
                                    .focusRequester(fixture.actionFocus)
                                    .onFocusChanged {
                                        fixture.chrome.focus.focused("settings", fixture.actionOwner, it.isFocused)
                                    }
                                    .focusable())
                            }
                        }
                    }
                }
            }
        }
        advance(300L)
        return fixture
    }

    private fun advance(milliseconds: Long) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun assertDurationArrivalKeepsGeometry(handheld: Boolean) {
        val fixture = mount(handheld = handheld)
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val loadingTimeline = rule.onNodeWithTag("timeline-slot").fetchSemanticsNode().boundsInRoot
        assertTrue("The initial timeline must occupy real layout space", loadingTimeline.height > 0f)

        rule.runOnIdle { fixture.duration.longValue = 120_000L }
        // Check both the transition and its final frame. A delayed resize would still
        // move the title and is not a substitute for reserving the device's real height.
        for (elapsed in listOf(16L, 80L, 240L)) {
            advance(elapsed)
            val currentHeader = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val currentTimeline = rule.onNodeWithTag("timeline-slot").fetchSemanticsNode().boundsInRoot
            assertEquals("Header moved while duration became available", header.top, currentHeader.top, 0.5f)
            assertEquals("Timeline changed its loading height", loadingTimeline.height, currentTimeline.height, 0.5f)
        }
    }

    @Test fun televisionTimelineReservesItsFinalHeightBeforeDurationArrives() {
        assertDurationArrivalKeepsGeometry(handheld = false)
    }

    @Test fun handheldTimelineUsesTouchGeometryEvenBeforeItCanSeek() {
        assertDurationArrivalKeepsGeometry(handheld = true)
    }

    @Test fun aLateTimelineRowExpandsUpwardsWithoutMovingTheBottomEdge() {
        val fixture = mount()
        val initialSlot = rule.onNodeWithTag("timeline-slot").fetchSemanticsNode().boundsInRoot
        val initialHeader = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot

        rule.runOnIdle { fixture.extraLine.value = true }
        // Apply the new content before advancing its size transition. The first
        // frame must keep the occupied height instead of jumping to the new size.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val firstSlot = rule.onNodeWithTag("timeline-slot").fetchSemanticsNode().boundsInRoot
        val firstHeader = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        assertEquals(initialSlot.height, firstSlot.height, 0.5f)
        assertEquals(initialHeader.top, firstHeader.top, 0.5f)

        rule.mainClock.advanceTimeBy(64L)
        rule.waitForIdle()
        val middleSlot = rule.onNodeWithTag("timeline-slot").fetchSemanticsNode().boundsInRoot
        val middleHeader = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        rule.mainClock.advanceTimeBy(240L)
        rule.waitForIdle()
        val finalSlot = rule.onNodeWithTag("timeline-slot").fetchSemanticsNode().boundsInRoot
        val finalHeader = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot

        assertTrue("The added row must increase the occupied height", finalSlot.height > initialSlot.height)
        assertTrue("The size transition must have an intermediate height", middleSlot.height > initialSlot.height)
        assertTrue("The added row must not appear at its final height immediately", middleSlot.height < finalSlot.height)
        assertTrue("The header must move upwards during expansion", middleHeader.top < initialHeader.top)
        assertTrue("The header must still be moving at the intermediate frame", middleHeader.top > finalHeader.top)
        for (slot in listOf(firstSlot, middleSlot, finalSlot)) {
            assertEquals("The timeline's bottom edge must remain anchored", initialSlot.bottom, slot.bottom, 0.5f)
        }
        assertEquals(finalSlot.height - initialSlot.height, initialHeader.top - finalHeader.top, 0.5f)
    }

    @Test fun loadingHandheldTimelineIgnoresTouchUntilItHasARealDuration() {
        val fixture = mount(handheld = true)
        fun tapTrack() = rule.onNodeWithTag("timeline").performTouchInput {
            click(Offset(width * 0.75f, 12f))
        }
        tapTrack()
        rule.runOnIdle {
            assertTrue(fixture.seeks.isEmpty())
            assertEquals(0, fixture.interactions)
        }

        rule.runOnIdle { fixture.duration.longValue = 120_000L }
        advance(240L)
        tapTrack()
        rule.runOnIdle {
            assertTrue(fixture.seeks.any { it > 0L })
            assertTrue(fixture.interactions > 0)
        }
    }

    @Test fun lateDurationDoesNotStealFocusFromAnExpandedOsdButton() {
        val fixture = mount(withFocus = true)
        rule.runOnIdle { fixture.actionFocus.requestFocus() }
        rule.onNodeWithTag("settings").assertIsFocused()

        rule.runOnIdle { fixture.duration.longValue = 120_000L }
        advance(300L)
        rule.onNodeWithTag("settings").assertIsFocused()
        rule.runOnIdle {
            assertTrue(fixture.chrome.osd.expanded)
            assertFalse(fixture.chrome.focus.seekFocused)
        }
    }

    @Test fun focusedCompactFallbackHandsOffToTheNewlyReadyTimeline() {
        val fixture = mount(withFocus = true, compact = true)
        rule.runOnIdle {
            assertEquals("compact-info", fixture.chrome.focus.lastKey)
            assertTrue(fixture.chrome.focus.compactFocused)
            assertFalse(fixture.chrome.focus.seekFocused)
        }

        rule.runOnIdle { fixture.duration.longValue = 120_000L }
        advance(300L)
        rule.onNodeWithTag("timeline").assertIsFocused()
        rule.runOnIdle { assertTrue(fixture.chrome.focus.seekFocused) }
        rule.onNodeWithTag("timeline").performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertTrue(fixture.seeks.any { it > 0L }) }
    }
}
