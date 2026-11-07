package com.ambient.tv.ui.tvheadend

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvBrowseState
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.ui.common.FilterBarSetting
import com.ambient.tv.ui.common.LocalFilterBarSetting
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real nested guide: vertical motion belongs to the complete sender row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class GuideFocusScrollTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(emptyRow: Int? = null) {
        val startRow = 8
        val browse = LiveTvBrowseState()
        val firstFocus = FocusRequester()
        val options = GuideOptions()
        val channels = (0 until 24).map { index ->
            LiveTvChannel("station-$index", "Station $index", index + 1, iconUrl = null)
        }
        val events = channels.flatMapIndexed { index, channel ->
            if (index == emptyRow) emptyList() else (0 until 4).map { slot ->
                LiveTvProgramme(
                    eventId = "${channel.uuid}:$slot", channelUuid = channel.uuid,
                    channelName = channel.name, title = "Programme $index-$slot",
                    startEpochSeconds = 5_400L + slot * 3_600L,
                    stopEpochSeconds = 9_000L + slot * 3_600L,
                )
            }
        }
        // Supplying the prepared listing avoids a background worker and wall-clock ticker.
        val listing = buildLiveTvChannelListing(channels, events, options, now = 7_200L)
        lateinit var controls: LiveTvBrowseControls
        var focusedRow: String? = null
        var rowPitch = 0f

        fun programme(row: Int, slot: Int = 0) = listing.eventsByChannel.getValue(channels[row].uuid)[slot]
    }

    private fun mount(emptyRow: Int? = null): Fixture {
        val fixture = Fixture(emptyRow)
        rule.mainClock.autoAdvance = true
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalFilterBarSetting provides FilterBarSetting(true) {},
            ) {
                AmbientTvTheme {
                    val controls = rememberLiveTvBrowseControls(fixture.browse, "guide")
                    SideEffect { fixture.controls = controls }
                    Box(Modifier.size(1_100.dp, 560.dp)) {
                        Guide(
                            browseState = fixture.browse, channels = fixture.channels,
                            events = fixture.events, imageHeaders = emptyMap(), onEvent = {},
                            firstContentFocusRequester = fixture.firstFocus,
                            focusTarget = TvhFocusTarget(
                                "event:${fixture.programme(fixture.startRow).eventId}",
                                fixture.channels[fixture.startRow].uuid,
                            ),
                            onFocused = { _, row -> fixture.focusedRow = row },
                            options = fixture.options, onOptions = {}, onWatch = {}, canWatch = true,
                            controls = controls, listing = fixture.listing,
                        )
                    }
                }
            }
        }
        // Complete initial measurement, nested-row composition and first centering first.
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(fixture.firstFocus.requestFocus()) }
        rule.waitForIdle()
        programme(fixture, fixture.startRow).assertIsFocused()
        fixture.rowPitch = channel(fixture, fixture.startRow + 1).bounds().top -
            channel(fixture, fixture.startRow).bounds().top
        assertTrue("The fixture has distinct adjacent rows", fixture.rowPitch > 0f)
        rule.mainClock.autoAdvance = false
        return fixture
    }

    private fun channel(fixture: Fixture, row: Int) =
        rule.onNode(hasText(fixture.channels[row].name) and hasClickAction())

    private fun programme(fixture: Fixture, row: Int, slot: Int = 0) =
        rule.onNode(hasText(fixture.programme(row, slot).title) and hasClickAction())

    private fun verticalList() = rule.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun tap(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.waitForIdle()
    }

    private fun offset(fixture: Fixture): Float = rule.runOnIdle {
        val position = checkNotNull(fixture.browse.position("guide"))
        val row = fixture.channels.indexOfFirst { it.uuid == position.itemId }
        check(row >= 0)
        // Measure the row pitch rather than depending on Guide padding or spacing constants.
        row * fixture.rowPitch + position.offset
    }

    @Test fun rapidDownMovesRetargetWithoutScrollingBackwards() {
        val fixture = mount()
        val samples = mutableListOf(offset(fixture))
        repeat(3) { step ->
            tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            rule.runOnIdle {
                assertEquals(fixture.channels[fixture.startRow + step + 1].uuid, fixture.focusedRow)
            }
            repeat(4) {
                advance(16)
                samples += offset(fixture)
            }
        }
        repeat(24) {
            advance(32)
            samples += offset(fixture)
        }
        for ((before, after) in samples.zipWithNext()) {
            assertTrue("Down must not reverse vertical motion: $samples", after >= before - 1f)
        }
        assertTrue("All three row changes must make progress", samples.last() - samples.first() > fixture.rowPitch * 2f)
        programme(fixture, fixture.startRow + 3).assertIsFocused()
    }

    @Test fun movingBetweenChannelAndProgrammesDoesNotRecenterTheRow() {
        val fixture = mount()
        val centered = offset(fixture)
        // A small manual scroll leaves the focused row visible but deliberately off-center.
        // Re-centering on each child focus change would now be an observable regression.
        verticalList().performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, fixture.rowPitch / 4f))
        }
        advance(1_200)
        val parked = offset(fixture)
        assertTrue("The manual scroll moved the row off-center", parked > centered + fixture.rowPitch / 8f)
        programme(fixture, fixture.startRow).assertIsFocused()

        val moves = listOf(
            AndroidKeyEvent.KEYCODE_DPAD_LEFT to channel(fixture, fixture.startRow),
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT to programme(fixture, fixture.startRow),
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT to programme(fixture, fixture.startRow, 1),
            AndroidKeyEvent.KEYCODE_DPAD_LEFT to programme(fixture, fixture.startRow),
        )
        for ((key, target) in moves) {
            tap(key)
            target.assertIsFocused()
            repeat(8) {
                advance(32)
                assertEquals("Horizontal focus must preserve vertical position", parked, offset(fixture), 1f)
            }
        }
    }

    @Test fun movingFocusToTheFilterStopsAnOngoingVerticalGlide() {
        val fixture = mount()
        val before = offset(fixture)
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        advance(80)
        val moving = offset(fixture)
        assertTrue("The glide has started", moving > before + 1f)
        assertTrue("Focus leaves before the destination is reached", moving < before + fixture.rowPitch - 2f)

        rule.runOnIdle { assertTrue(fixture.controls.filterBarFocus.requestFocus()) }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(fixture.controls.filterFocusState.allowContentAutoFocus) }
        assertTrue("Focus is in the filter above the guide", rule.onNode(isFocused()).bounds().bottom <= verticalList().bounds().top)
        val stopped = offset(fixture)
        repeat(20) {
            advance(32)
            assertEquals("The previous row must not keep pulling the guide after focus leaves", stopped, offset(fixture), 1f)
        }
    }

    @Test fun aChannelWithoutEpgKeepsTheSameRowHeightAndSpacing() {
        val fixture = mount(emptyRow = 9)
        val before = channel(fixture, 8).bounds()
        val empty = channel(fixture, 9).bounds()
        val after = channel(fixture, 10).bounds()
        val programmeHeight = programme(fixture, 8).bounds().height
        assertEquals("Sender and programme targets have the same height", programmeHeight, before.height, 1f)
        assertEquals("Missing EPG must not collapse the sender row", programmeHeight, empty.height, 1f)
        assertEquals("Rows around missing EPG keep equal spacing", empty.top - before.top, after.top - empty.top, 1f)
    }
}
