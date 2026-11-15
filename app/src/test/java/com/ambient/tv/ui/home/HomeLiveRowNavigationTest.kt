package com.ambient.tv.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.ambient.tv.data.local.ClockSettings
import com.ambient.tv.data.local.HomeHeroStyle
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.BrowseFocusReturnScope
import com.ambient.tv.ui.common.BrowseFocusReturnState
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.LocalBrowseFocusReturnState
import com.ambient.tv.ui.common.LocalClockSettings
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.NavReselect
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Home rows, including the alpha selection and the opaque part of the hero mask. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLiveRowNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Frame(val focus: BrowseFocusKey?, val bounds: Rect?, val brightPixels: Int) {
        fun visibleBelowHero(height: Int): Boolean = bounds?.let {
            it.width > 40f && it.height > 40f && it.top >= height * 0.44f * 0.96f - 3f &&
                it.bottom <= height + 1f && brightPixels > 50
        } == true
    }

    private class Fixture {
        val before = ContentRowData(id = "before-live", title = "Above Live TV", style = MediaTileStyle.LANDSCAPE,
            items = List(12) { homeTrailerTestItem("above-$it", "Above $it") }, showDescription = false)
        val after = ContentRowData(id = "after-live", title = "Below Live TV", style = MediaTileStyle.LANDSCAPE,
            items = List(12) { homeTrailerTestItem("below-$it", "Below $it") }, showDescription = false)
        val row = "live-tv:iptv"
        val channels = List(4) { index -> HomeLiveTvItem(
            LiveTvChannel("channel-$index", "Channel $index", index + 1, null, sourceId = "iptv:test"), null,
        ) }
        val initial = HomeFocusSnapshot(homeLiveTvFocusKey(channels.first()), row)
        val state = HomeUiState.Loaded(
            rows = listOf(before, after), rowKeys = listOf(before.id, row, after.id),
            tvRows = mapOf(row to HomeTvRow(channels, "Live TV")),
            focusedRatingKey = initial.ratingKey, focusedRowId = initial.rowId,
            playbackReturnRevision = 1,
        )
        val hero = MutableStateFlow<HomeHeroContent?>(null)
        lateinit var owner: BrowseFocusReturnState
        lateinit var bitmap: Bitmap
        val frames = mutableListOf<Frame>()
    }

    @Before fun resetReselect() { NavReselect.restoreTop.value = false }
    @After fun restoreClock() { rule.mainClock.autoAdvance = true }

    private fun mount(mode: PerformanceMode = PerformanceMode.LOW): Fixture {
        val fixture = Fixture()
        rule.setContent {
            CompositionLocalProvider(
                LocalHandheldUi provides false,
                LocalClockSettings provides ClockSettings(display = ClockSettings.DISPLAY_OFF),
                LocalPerformanceProfile provides PerformanceProfile.resolve(mode, lowEndDevice = false),
            ) {
                AmbientTvTheme {
                    BrowseFocusReturnScope {
                        val owner = checkNotNull(LocalBrowseFocusReturnState.current)
                        SideEffect { fixture.owner = owner }
                        LibraryContent(
                            state = fixture.state, loadingRowStyles = emptyList(),
                            heroContent = fixture.hero, homeHeroStyleKey = HomeHeroStyle.STATIC,
                            homeHeroIntensityPercent = 0, homeHeroSpreadKey = HomeHeroStyle.SOFT,
                            focusSnapshot = fixture.initial, onItemClick = {}, onItemFocused = { _, _ -> },
                            onOpenRelevantSettings = {}, onWatchLiveChannel = { _, _ -> },
                            onLiveTvFocused = { _, _ -> }, onShortcutFocused = {}, onOpenHomeContent = {},
                            tvheadendImageHeaders = emptyMap(), entranceProgress = { 1f },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        fixture.bitmap = rule.runOnIdle {
            val decor = rule.activity.window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        }
        rule.mainClock.autoAdvance = false
        awaitVisible(fixture, fixture.row)
        return fixture
    }

    private fun drawFrame(fixture: Fixture): Frame {
        val bounds = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        var brightPixels = 0
        rule.runOnUiThread {
            fixture.bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            rule.activity.window.decorView.draw(Canvas(fixture.bitmap))
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                val left = bounds.left.toInt().coerceIn(0, fixture.bitmap.width)
                val right = bounds.right.toInt().coerceIn(left, fixture.bitmap.width)
                val top = maxOf(bounds.top.toInt(), (fixture.bitmap.height * .5f).toInt()).coerceIn(0, fixture.bitmap.height)
                val bottom = bounds.bottom.toInt().coerceIn(top, fixture.bitmap.height)
                for (y in top until bottom step 2) for (x in left until right step 2) {
                    val pixel = fixture.bitmap.getPixel(x, y)
                    // A hidden row still has semantics and focus. Its white text/icon and
                    // coloured focus border must actually reach the rendered frame.
                    if (maxOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel),
                            android.graphics.Color.blue(pixel)) > 140) brightPixels++
                }
            }
        }
        return Frame(fixture.owner.focusedContentKey, bounds, brightPixels).also(fixture.frames::add)
    }

    private fun advanceFrame(fixture: Fixture): Frame {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        return drawFrame(fixture)
    }

    private fun awaitVisible(fixture: Fixture, row: String): Frame {
        var previous: Rect? = null
        var stable = 0
        repeat(150) {
            val frame = advanceFrame(fixture)
            if (frame.focus?.row == row && frame.visibleBelowHero(fixture.bitmap.height)) {
                stable = if (frame.bounds == previous) stable + 1 else 0
                if (stable >= 8) return frame
            } else stable = 0
            previous = frame.bounds
        }
        fail("Focused Home row must be painted below the hero: expected=$row, tail=${fixture.frames.takeLast(8)}")
        error("unreachable")
    }

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
    }

    private fun assertRemainsVisible(fixture: Fixture, row: String) {
        repeat(65) {
            val frame = advanceFrame(fixture)
            assertEquals(row, frame.focus?.row)
            assertTrue("Focused row must remain painted, including after pending visual timeout: $frame",
                frame.visibleBelowHero(fixture.bitmap.height))
        }
    }

    @Test fun upAfterLivePlaybackShowsTheUpperRowAndLaterEntriesStillCenter() {
        val fixture = mount(PerformanceMode.BALANCED)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        awaitVisible(fixture, fixture.before.id)
        assertRemainsVisible(fixture, fixture.before.id)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        awaitVisible(fixture, fixture.row)
        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        awaitVisible(fixture, fixture.before.id)
        assertRemainsVisible(fixture, fixture.before.id)
    }

    @Test fun rapidUpDownReversalsCannotLeaveTheFocusedRowMaskedOrAboveTheViewport() {
        val fixture = mount()
        repeat(3) {
            press(AndroidKeyEvent.KEYCODE_DPAD_UP)
            repeat(3) { advanceFrame(fixture) }
            press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            repeat(2) { advanceFrame(fixture) }
            press(AndroidKeyEvent.KEYCODE_DPAD_UP)
            awaitVisible(fixture, fixture.before.id)
            assertRemainsVisible(fixture, fixture.before.id)
            press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
            awaitVisible(fixture, fixture.row)
        }
    }
}
