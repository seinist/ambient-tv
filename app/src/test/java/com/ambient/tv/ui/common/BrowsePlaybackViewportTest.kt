package com.ambient.tv.ui.common

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Inspect actual draws, including later relocation frames, rather than just the idle focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalFoundationApi::class)
class BrowsePlaybackViewportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Shape { GRID, LIST, ROWS }
    private data class Frame(
        val focus: BrowseFocusKey?,
        val positions: Map<String, BrowseViewportPosition>,
        val targetVisible: Boolean,
    )

    private class Fixture(val shape: Shape) {
        val items = (0 until 90).map { "item-$it" }
        val rows = (0 until 12).map { row -> "row-$row" to (0 until 40).map { "row-$row:item-$it" } }
        val initial = if (shape == Shape.ROWS) BrowseFocusKey("row-0", "row-0:item-0", true)
            else BrowseFocusKey(null, "item-0", true)
        var returned by mutableStateOf<Pair<Long, BrowseFocusKey>?>(null)
        var enabled by mutableStateOf(true)
        var permit by mutableStateOf(true)
        var phase by mutableStateOf(0)
        lateinit var owner: BrowseFocusReturnState
        lateinit var controller: BrowsePlaybackReturn
        lateinit var bitmap: Bitmap
        var grid: LazyGridState? = null
        var list: LazyListState? = null
        val innerRows = mutableMapOf<String, LazyListState>()
        val firstLayouts = mutableMapOf<Int, Frame>()
        val draws = mutableMapOf<Int, MutableList<Frame>>()
        val centeredRows = mutableListOf<String>()

        fun frame(): Frame {
            val target = returned?.second ?: initial
            val positions = buildMap {
                grid?.let { put("grid", BrowseViewportPosition(it.firstVisibleItemIndex,
                    it.firstVisibleItemScrollOffset, it.isScrollInProgress)) }
                list?.let { put("list", BrowseViewportPosition(it.firstVisibleItemIndex,
                    it.firstVisibleItemScrollOffset, it.isScrollInProgress)) }
                innerRows[target.row]?.let { put("inner", BrowseViewportPosition(it.firstVisibleItemIndex,
                    it.firstVisibleItemScrollOffset, it.isScrollInProgress)) }
            }
            val visible = when (shape) {
                Shape.GRID -> grid?.layoutInfo?.visibleItemsInfo?.any { it.key == target.item } == true
                Shape.LIST -> list?.layoutInfo?.visibleItemsInfo?.any { it.key == target.item } == true
                Shape.ROWS -> list?.layoutInfo?.visibleItemsInfo?.any { it.key == target.row } == true &&
                    innerRows[target.row]?.layoutInfo?.visibleItemsInfo?.any { it.key == target.item } == true
            }
            return Frame(owner.focusedContentKey, positions, visible)
        }
    }

    @Before fun clearReselect() { NavReselect.restoreTop.value = false }
    @After fun restoreClock() { rule.mainClock.autoAdvance = true }

    @Composable
    private fun Fixture.Content() {
        val currentPhase = phase
        BrowseFocusInitialTarget(initial.item, initial.row)
        val playback = rememberBrowsePlaybackReturn(returned?.first, returned?.second, enabled)
        val focusOwner = checkNotNull(LocalBrowseFocusReturnState.current)
        SideEffect { owner = focusOwner; controller = playback }
        Box(Modifier.size(600.dp, 300.dp).then(playback.modifier)
            .drawWithContent {
                draws.getOrPut(currentPhase) { mutableListOf() }.add(frame())
                drawContent()
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    firstLayouts.putIfAbsent(currentPhase, frame())
                    placeable.place(0, 0)
                }
            }) {
            when (shape) {
                Shape.GRID -> {
                    val state = rememberBrowseGridState(itemKeys = items)
                    grid = state
                    LazyVerticalGrid(GridCells.Fixed(3), state = state,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it }) { id ->
                            Tile(id, Modifier.fillMaxWidth().height(88.dp))
                        }
                    }
                }
                Shape.LIST -> {
                    val state = rememberBrowseListState(itemKeys = items)
                    list = state
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it }) { id -> Tile(id, Modifier.fillMaxWidth().height(88.dp)) }
                    }
                }
                Shape.ROWS -> {
                    val state = rememberBrowseRowColumnState()
                    list = state
                    RegisterBrowseFocusRows(state, rows)
                    val focusedRows = remember(state) { MutableStateFlow<String?>(null) }
                    val centering = rememberRowCenteringState(state)
                    LaunchedEffect(state) {
                        var firstFocus = true
                        focusedRows.collectLatest { row ->
                            val index = rows.indexOfFirst { it.first == row }
                            if (index < 0) return@collectLatest
                            if (firstFocus) {
                                firstFocus = false
                                if (state.layoutInfo.visibleItemsInfo.any { it.index == index }) return@collectLatest
                            }
                            centering.centerItem(index)
                            centeredRows += checkNotNull(row)
                        }
                    }
                    // Home/SmartTube center outer rows in a separate collector; the generic
                    // BringIntoView request only handles the inner row's comfort zone.
                    CompositionLocalProvider(LocalBringIntoViewSpec provides ExplicitRowScrollSpec) {
                        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(rows, key = { it.first }) { (row, items) ->
                                BrowseFocusRow(row) {
                                    val inner = rememberBrowseRowState(itemKeys = items)
                                    innerRows[row] = inner
                                    var wasFocused by remember { mutableStateOf(false) }
                                    Column(Modifier.onFocusChanged {
                                        if (it.hasFocus && !wasFocused) focusedRows.value = row
                                        wasFocused = it.hasFocus
                                    }.focusGroup()) {
                                        Text(row)
                                        CompositionLocalProvider(LocalBringIntoViewSpec provides TvBringIntoViewSpec) {
                                            LazyRow(state = inner, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                items(items, key = { it }) { id -> Tile(id, Modifier.size(144.dp, 88.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Tile(id: String, modifier: Modifier) {
        Box(modifier.background(Color.DarkGray).browseFocusTarget(id).focusable()) { Text(id) }
    }

    private fun mount(shape: Shape): Fixture {
        val fixture = Fixture(shape)
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false,
                LocalPlaybackFocusRequest provides { request -> if (fixture.permit) request() else false }) {
                AmbientTvTheme { BrowseFocusReturnScope { fixture.Content() } }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(fixture.owner.requestContentFocus(fixture.initial) == true) }
        rule.waitForIdle()
        fixture.bitmap = rule.runOnIdle {
            val view = rule.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        }
        drawFrame(fixture)
        assertTrue("The fixture must execute real Compose draw modifiers", !fixture.draws[0].isNullOrEmpty())
        rule.mainClock.autoAdvance = false
        return fixture
    }

    private fun drawFrame(fixture: Fixture) {
        rule.runOnUiThread {
            fixture.bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            rule.activity.window.decorView.draw(Canvas(fixture.bitmap))
        }
    }

    private fun advanceFrame(fixture: Fixture) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        drawFrame(fixture)
    }

    private fun awaitVisibleFrame(fixture: Fixture) {
        repeat(130) {
            advanceFrame(fixture)
            if (!fixture.controller.pending && !fixture.draws[fixture.phase].isNullOrEmpty()) return
        }
        fail("Return never drew: pending=${fixture.controller.pending}, frame=${fixture.frame()}, " +
            "firstLayout=${fixture.firstLayouts[fixture.phase]}, drawCounts=${fixture.draws.mapValues { it.value.size }}")
    }

    private fun returnTo(fixture: Fixture, revision: Long, target: BrowseFocusKey) {
        rule.runOnIdle {
            fixture.phase++
            fixture.returned = revision to target
        }
        awaitVisibleFrame(fixture)
    }

    private fun assertStableVisibleReturn(fixture: Fixture, target: BrowseFocusKey) {
        val phase = fixture.phase
        val firstLayout = fixture.firstLayouts[phase]
        assertNotNull("The return must actually be laid out", firstLayout)
        assertTrue("The first layout must already contain the returned item", firstLayout!!.targetVisible)
        val firstDraw = checkNotNull(fixture.draws[phase]?.firstOrNull())
        assertEquals("The first visible frame must focus the returned leaf", target, firstDraw.focus)
        assertTrue(firstDraw.targetVisible)
        assertTrue("No viewport animation may survive draw release", firstDraw.positions.values.none { it.scrolling })
        // Delayed focus/row callbacks still get their frames. They must not shift the view
        // after it has become visible, even if the expected final focus was already correct.
        repeat(100) { advanceFrame(fixture) }
        rule.runOnIdle {
            assertEquals(firstDraw, fixture.frame())
            fixture.draws.getValue(phase).forEach { assertEquals(firstDraw, it) }
        }
    }

    @Test fun repeatedGridReturnsReplaceTheOldViewportBeforeAnyVisibleFrame() {
        val fixture = mount(Shape.GRID)
        for ((revision, index) in listOf(1L to 61, 2L to 79)) {
            val target = BrowseFocusKey(null, "item-$index", true)
            returnTo(fixture, revision, target)
            assertStableVisibleReturn(fixture, target)
        }
    }

    @Test fun nestedRowsPrepareBothTheOuterRowAndTheLastPlayedCard() {
        val fixture = mount(Shape.ROWS)
        val target = BrowseFocusKey("row-7", "row-7:item-25", true)
        returnTo(fixture, 1L, target)
        assertStableVisibleReturn(fixture, target)
        assertTrue("The independent row-centering coroutine must finish", "row-7" in fixture.centeredRows)
    }

    @Test fun aPartlyVisibleListItemFinishesItsComfortZoneScrollBeforeDrawing() {
        val fixture = mount(Shape.LIST)
        val target = BrowseFocusKey(null, "item-3", true)
        rule.runOnIdle {
            assertTrue(fixture.list!!.layoutInfo.visibleItemsInfo.any { it.key == target.item })
        }
        returnTo(fixture, 1L, target)
        assertStableVisibleReturn(fixture, target)
    }

    @Test fun anUnavailablePresentationWaitsAndADialogCannotLeaveTheGateStuck() {
        val fixture = mount(Shape.GRID)
        val target = BrowseFocusKey(null, "item-61", true)
        rule.runOnIdle {
            fixture.enabled = false
            fixture.returned = 1L to target
        }
        repeat(100) { advanceFrame(fixture) }
        rule.runOnIdle {
            assertEquals(fixture.initial, fixture.owner.focusedContentKey)
            assertEquals(0, fixture.grid!!.firstVisibleItemIndex)
            fixture.permit = false
            fixture.enabled = true
            fixture.phase++
        }
        awaitVisibleFrame(fixture)
        rule.runOnIdle {
            assertFalse(fixture.owner.hasFocus(target))
            assertFalse(fixture.grid!!.isScrollInProgress)
            assertTrue("A denied handoff must eventually reveal usable content", !fixture.draws[fixture.phase].isNullOrEmpty())
            fixture.permit = true
        }
        repeat(8) { advanceFrame(fixture) }
        assertFalse("Finishing a blocked revision must not later steal focus", fixture.owner.hasFocus(target))
    }
}
