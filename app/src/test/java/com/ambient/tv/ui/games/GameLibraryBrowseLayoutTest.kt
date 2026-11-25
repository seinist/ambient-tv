package com.ambient.tv.ui.games

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.library.coverAspectRatio
import com.ambient.tv.ui.common.BrowseFocusKey
import com.ambient.tv.ui.common.BrowseFocusReturnScope
import com.ambient.tv.ui.common.BrowseFocusReturnState
import com.ambient.tv.ui.common.LocalBrowseFocusReturnState
import com.ambient.tv.ui.common.RegisterBrowseFocusRows
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.TvLazyVerticalGrid
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real lazy shells, cards and native D-pad events exercise the library's layout contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class GameLibraryBrowseLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun game(id: String, title: String = id, system: String = "snes") = LibraryGame(
        fileId = id, folderId = "folder", sourceId = "storage", path = "$id.sfc", filename = "$id.sfc",
        systemId = system, title = title, internalTitle = null,
        size = 123, modified = 1, favorite = false, lastPlayed = 0,
    )

    private fun press(code: Int) {
        rule.runOnUiThread {
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, code, 0))
            rule.activity.dispatchKeyEvent(AndroidKeyEvent(0L, 1L, AndroidKeyEvent.ACTION_UP, code, 0))
        }
        rule.waitForIdle()
    }

    private fun assertAlphabetRoundTrip(list: Boolean) {
        val games = listOf(game("alpha", "Alpha"), game("mario", "Mario"))
        val requests = mutableMapOf<String, FocusRequester>()
        val firstFocus = FocusRequester()
        var focusedId by mutableStateOf("alpha")
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxSize()) {
                        GameLibraryAlphabetLayout(
                            letters = listOf('A', 'M'),
                            selectedLetter = games.first { it.fileId == focusedId }.title.first(),
                            visibleLetter = { 'A' },
                            firstContentFocus = firstFocus,
                            onReturnToContent = { requests.getValue(focusedId).requestFocus() },
                            onSelect = {},
                            modifier = Modifier.width(750.dp).height(500.dp).focusGroup(),
                        ) {
                            if (list) {
                                TvLazyColumn(Modifier.weight(1f).fillMaxHeight(), allowUpExit = true) {
                                    items(games, key = { it.fileId }) { entry ->
                                        GameListEntry(entry,
                                            modifier = if (entry.fileId == "alpha")
                                                Modifier.focusRequester(firstFocus) else Modifier,
                                            onPlay = {}, onInfo = {}, onFavorite = {},
                                            onFocus = { focusedId = entry.fileId },
                                            onRequester = { requests[entry.fileId] = it },
                                            onDisposed = { requests.remove(entry.fileId) })
                                    }
                                }
                            } else {
                                TvLazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).fillMaxHeight()) {
                                    items(games, key = { it.fileId }) { entry ->
                                        GameTile(entry,
                                            modifier = if (entry.fileId == "alpha")
                                                Modifier.focusRequester(firstFocus) else Modifier,
                                            onPlay = {}, onInfo = {}, onFocus = { focusedId = entry.fileId },
                                            onRequester = { requests[entry.fileId] = it },
                                            onDisposed = { requests.remove(entry.fileId) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(requests.getValue("mario").requestFocus()) }
        rule.waitForIdle()
        // In the list the favorite is the rightmost independently focusable control of the same
        // game. A TV grid card shows it as a badge, so the card itself is the rightmost target.
        if (list) {
            rule.onNodeWithTag("game-favorite:mario")
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            rule.waitForIdle()
        } else {
            rule.onAllNodesWithTag("game-favorite:mario").assertCountEquals(0)
        }
        press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("alphabet-letter-M").assertIsFocused()
        press(AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNodeWithTag("game-select:mario").assertIsFocused()
    }

    @Test fun gridRightEdgeEntersCurrentLetterAndLeftReturnsToTheSameGame() {
        assertAlphabetRoundTrip(list = false)
    }

    @Test fun listRightEdgeEntersCurrentLetterAndLeftReturnsToTheSameGame() {
        assertAlphabetRoundTrip(list = true)
    }

    private fun assertHandheldAlphabetTracksViewport(list: Boolean) {
        val games = ('A'..'Z').flatMap { letter ->
            List(6) { index -> game("$letter-$index", "$letter Game $index") }
        }
        val parkedFocus = FocusRequester()
        val requests = mutableMapOf<String, FocusRequester>()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.size(1.dp).focusRequester(parkedFocus).testTag("parked-focus").focusable())
                        Box(Modifier.width(320.dp).height(600.dp)) {
                            GameLibraryGames(
                                games = games, view = if (list) "list" else "grid",
                                section = "snes", sortKey = "name", gridColumns = 8, listRows = 7,
                                controls = rememberGameLibraryBrowseControls(),
                                focusedId = "A-0", onFocused = {}, itemRequests = requests,
                                listState = rememberLazyListState(), gridState = rememberLazyGridState(),
                                rowsState = rememberLazyListState(),
                                onSelect = {}, onOpenDetails = {}, onFavorite = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(parkedFocus.requestFocus()) }
        rule.onNodeWithTag("alphabet-letter-A").assertIsSelected()

        rule.onNodeWithTag("alphabet-letter-M").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("alphabet-letter-M").assertIsSelected()
        // A touch alphabet jump must not schedule a game focus that drags the viewport back.
        rule.onNodeWithTag("parked-focus").assertIsFocused()

        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(6)
        rule.waitForIdle()
        // The last jump was M and the saved game focus is A; only the visible viewport is B.
        rule.onNodeWithTag("alphabet-letter-B").assertIsSelected()
        rule.onNodeWithTag("parked-focus").assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneGridAlphabetTracksViewportAndNeverRequestsGameFocus() {
        assertHandheldAlphabetTracksViewport(list = false)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun phoneListAlphabetTracksViewportAndNeverRequestsGameFocus() {
        assertHandheldAlphabetTracksViewport(list = true)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun narrowPhoneRowsFitWholeCardsAndKeepNativeArtworkRatiosAfterResize() {
        val entries = listOf(
            game("snes-wide").copy(coverWidth = 560, coverHeight = 400),
            game("panorama").copy(coverWidth = 1200, coverHeight = 400),
        )
        var viewportWidth by mutableStateOf(312.dp)
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Column(Modifier.width(viewportWidth)) {
                        for (entry in entries) {
                            GameLibraryCoverRow(listOf(entry), 216.dp, entry.fileId,
                                modifier = Modifier.testTag("row:${entry.fileId}")) { item, tileModifier, slotRatio ->
                                GameTile(item, tileModifier, artworkSlotAspectRatio = slotRatio,
                                    rowKey = entry.fileId,
                                    onPlay = {}, onInfo = {}, onFocus = {},
                                    onRequester = {}, onDisposed = {})
                            }
                        }
                    }
                }
            }
        }
        fun assertFits() {
            rule.waitForIdle()
            for (entry in entries) {
                val row = rule.onNodeWithTag("row:${entry.fileId}").fetchSemanticsNode().boundsInRoot
                val tile = rule.onNodeWithTag("game-select:${entry.fileId}").fetchSemanticsNode().boundsInRoot
                val slot = rule.onNodeWithTag("game-cover-slot:${entry.fileId}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val cover = rule.onNodeWithTag("game-cover:${entry.fileId}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Whole ${entry.fileId} card must fit between the row insets", tile.width <= row.width - 8f + 1f)
                assertTrue(tile.left >= row.left && tile.right <= row.right)
                assertEquals("Both 9dp card insets remain inside the viewport", tile.width - 18f, slot.width, 1f)
                assertEquals(216f, slot.height, 1f)
                assertTrue(cover.width <= slot.width && cover.height <= slot.height)
                assertEquals(entry.coverAspectRatio, cover.width / cover.height, .03f)
            }
        }
        assertFits()
        rule.runOnIdle { viewportWidth = 240.dp }
        assertFits()
    }

    @Test fun playbackReturnRevealsAnUncomposedGameInAnUncomposedConsoleRow() {
        val rows = listOf("nes", "snes", "gb", "gba", "psp", "psx").map { system ->
            system to List(24) { index -> game("$system-$index", system = system) }
        }
        val target = BrowseFocusKey("psx", "psx-23", true)
        lateinit var owner: BrowseFocusReturnState
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    BrowseFocusReturnScope {
                        val currentOwner = checkNotNull(LocalBrowseFocusReturnState.current)
                        SideEffect { owner = currentOwner }
                        val rowsState = rememberLazyListState()
                        RegisterBrowseFocusRows(rowsState, remember(rows) {
                            rows.map { (system, entries) -> system to entries.map { it.fileId } }
                        })
                        TvLazyColumn(Modifier.width(600.dp).height(400.dp), state = rowsState) {
                            items(rows, key = { it.first }) { (system, entries) ->
                                GameLibraryCoverRow(entries, 148.dp, system) { entry, tileModifier, slotRatio ->
                                    GameTile(entry, tileModifier, artworkSlotAspectRatio = slotRatio,
                                        rowKey = system, onPlay = {}, onInfo = {}, onFocus = {},
                                        onRequester = {}, onDisposed = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("game-select:psx-23").assertCountEquals(0)
        rule.runOnIdle { owner.preparePlaybackReturn(1L, target) }
        rule.waitForIdle()
        var focused = false
        repeat(8) {
            if (!focused) {
                focused = rule.runOnIdle { owner.requestContentFocus(target) == true }
                rule.waitForIdle()
            }
        }
        assertTrue("Both vertical and horizontal viewports must prepare the exact return target", focused)
        rule.onNodeWithTag("game-select:psx-23").assertIsDisplayed().assertIsFocused()
    }
}
