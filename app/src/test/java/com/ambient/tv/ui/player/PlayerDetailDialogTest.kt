package com.ambient.tv.ui.player

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ambient.tv.R
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.acceleratedDpadNavigation
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real OSD dialog, including its parent remote-key accelerator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class PlayerDetailDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // Explicit lines guarantee overflow even when Robolectric does not measure soft wrapping.
    private val longText = (1..60).joinToString("\n") { "Paragraph $it of the complete source description." }
    private var closeCalls = 0

    private fun detail() = PlayerDetailInfo(
        osdPlayer = OsdPlayer.YOUTUBE,
        title = "Playing video",
        artShape = PlayerDetailArtShape.WIDE,
        eyebrow = "Source channel",
        meta = listOf("Today", "10min"),
        overview = longText,
        system = MediaSystem.YOUTUBE,
        contentKey = "youtube:abcdefghijk",
    )

    private fun show(initial: PlayerDetailInfo = detail(), handheld: Boolean = false): MutableState<PlayerDetailInfo> {
        val state = mutableStateOf(initial)
        rule.mainClock.autoAdvance = true
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides handheld) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize().acceleratedDpadNavigation(enabled = !handheld)) {
                        PlayerDetailDialog(state.value, onClose = { closeCalls++ })
                    }
                }
            }
        }
        rule.waitForIdle()
        text().assertIsFocused()
        close().assertIsDisplayed()
        return state
    }

    private fun text() = rule.onNodeWithTag("player-detail-text")
    private fun close() = rule.onNode(hasText(rule.activity.getString(R.string.action_close)) and hasClickAction())
    private fun scrollbar() = rule.onNodeWithContentDescription(rule.activity.getString(R.string.common_scrollbar))
    private fun offset() = text().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun maxOffset() = text().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun requireOverflow() {
        assertTrue("The fixture must overflow the actual OSD reading viewport", maxOffset() > 0f)
    }

    private fun key(action: Int, code: Int, repeatCount: Int = 0) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, action, code, repeatCount))
        }
        rule.waitForIdle()
    }

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

    @Test fun heldReadingStopsOnReleaseAndOnlyANewBoundaryPressReachesClose() {
        show()
        requireOverflow()
        rule.mainClock.autoAdvance = false
        try {
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            advance(650)
            val first = offset()
            assertTrue(first > 0f)
            // Real native repeats pass through the same page-level accelerator as the player.
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 15)
            advance(400)
            assertTrue("Holding should keep reading, without moving focus out of the dialog", offset() > first)
            text().assertIsFocused()

            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            val released = offset()
            advance(800)
            assertEquals("Releasing a held key must leave no queued scroll animations", released, offset(), 1f)

            var pages = 0
            while (offset() < maxOffset()) {
                assertTrue("Page Down must eventually reach the final source paragraph", pages++ < 30)
                tap(AndroidKeyEvent.KEYCODE_PAGE_DOWN)
                advance(400)
            }
            text().assertIsFocused()
            // Even a repeat at the boundary must keep this press owned by the text.
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP)
            advance(650)
            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_UP)
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            advance(1_200)
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 15)
            advance(300)
            assertEquals(maxOffset(), offset(), 1f)
            text().assertIsFocused()
            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            text().assertIsFocused()

            tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            advance(32)
            close().assertIsFocused().assertIsDisplayed()
            tap(AndroidKeyEvent.KEYCODE_DPAD_UP)
            advance(32)
            text().assertIsFocused()
            assertEquals(0, closeCalls)
        } finally {
            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_UP)
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun disappearingFocusedScrollbarReturnsToTheText() {
        val state = show()
        requireOverflow()
        tap(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        scrollbar().assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        text().assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        scrollbar().assertIsFocused()

        rule.runOnIdle { state.value = state.value.copy(overview = "Short source description") }
        rule.waitForIdle()
        assertEquals(0f, maxOffset(), 1f)
        scrollbar().assertDoesNotExist()
        text().assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        close().assertIsFocused()
        tap(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        text().assertIsFocused()
    }

    @Test fun enrichmentKeepsReadingPositionAndANewPlayingItemStartsAtTheTop() {
        val initial = detail().copy(
            artworkUrl = "android.resource://${rule.activity.packageName}/${R.drawable.ic_ambient_tv}",
        )
        val state = show(initial)
        requireOverflow()
        tap(AndroidKeyEvent.KEYCODE_PAGE_DOWN)
        val before = offset()
        assertTrue(before > 0f)

        rule.runOnIdle {
            state.value = state.value.copy(
                title = "Enriched video",
                artworkUrl = "android.resource://${rule.activity.packageName}/${R.drawable.system_youtube}",
            )
        }
        rule.waitForIdle()
        assertEquals("Metadata belongs to the same playing item", before, offset(), 1f)
        text().assertIsFocused()

        rule.runOnIdle { state.value = state.value.copy(contentKey = "youtube:lmnopqrstuv") }
        rule.waitForIdle()
        assertEquals(0f, offset(), 1f)
        text().assertIsFocused()

        tap(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        scrollbar().assertIsFocused()
        rule.mainClock.autoAdvance = false
        try {
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            advance(650)
            assertTrue(offset() > 0f)
            rule.runOnUiThread { state.value = state.value.copy(contentKey = "youtube:wxyz1234567") }
            advance(32)
            assertEquals("Changing content cancels the previous scrollbar gesture", 0f, offset(), 1f)

            // A low repeat count bypasses page acceleration and reaches the dialog directly.
            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, repeatCount = 1)
            advance(650)
            assertEquals("A held key must not restart scrolling the replacement item's text", 0f, offset(), 1f)
            scrollbar().assertIsFocused()
            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN)

            key(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            advance(650)
            assertTrue("After release, a fresh press can scroll the new item's text", offset() > 0f)
        } finally {
            key(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            rule.mainClock.autoAdvance = true
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun compactDialogWrapsTechnicalChipsAfterContentAndPinsCloseWhileReading() {
        // Padding and gaps alone force these chips onto several lines, independent of fonts.
        val chips = (1..30).map { "C$it" }
        val overview = "The playing content comes first."
        val state = show(detail().copy(overview = overview, chips = chips), handheld = true)
        val contentBounds = text().fetchSemanticsNode().boundsInRoot
        val overviewBounds = rule.onNodeWithText(overview).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val chipBounds = chips.map { rule.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        assertTrue("Technical properties follow the content description", chipBounds.first().top >= overviewBounds.bottom)
        assertTrue("The compact chip strip must wrap", chipBounds.last().top > chipBounds.first().top)
        chipBounds.forEach { bounds ->
            assertTrue("A technical chip must stay inside the dialog", bounds.left >= contentBounds.left - 1f &&
                bounds.right <= contentBounds.right + 1f)
        }
        val closeBefore = close().fetchSemanticsNode().boundsInRoot

        rule.runOnIdle { state.value = state.value.copy(overview = longText) }
        rule.waitForIdle()
        requireOverflow()
        rule.onNodeWithText(chips.last()).performScrollTo().assertIsDisplayed()
        assertTrue(offset() > 0f)
        val closeAfter = close().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(closeBefore.top, closeAfter.top, 1f)
        assertEquals(closeBefore.bottom, closeAfter.bottom, 1f)
        close().performClick()
        assertEquals(1, closeCalls)
    }
}
