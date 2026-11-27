package com.ambient.tv.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import com.ambient.tv.util.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.games.metadata.GameArtworkPriority
import com.ambient.tv.ui.common.ContentRow
import com.ambient.tv.ui.games.LocalGameArtworkPriority
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import com.ambient.tv.ui.theme.screenGutter
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h480dp-land-mdpi")
class GameHomePresentationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun game(id: String, system: String, width: Int) = LibraryGame(
        fileId = id, folderId = "folder", sourceId = "local", path = "$id.rom", filename = "$id.rom",
        systemId = system, title = id, internalTitle = null, size = 1, modified = 0,
        favorite = false, lastPlayed = 0, coverWidth = width, coverHeight = 400,
    )

    private fun row(games: List<LibraryGame>) = gameHomeRows(RuntimeEnvironment.getApplication(),
        listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES,
            contentKey = HomeRowDefinition.GAMES_ALL)), games).single()

    @Test fun homeKeepsMixedBoxShapesAndSeparatesTapFromExplicitInfo() {
        val priority = GameArtworkPriority()
        val row = row(listOf(game("A SNES", "snes", 560), game("B PSP", "psp", 240)))
        val selections = mutableListOf<String>()
        val details = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true, LocalGameArtworkPriority provides priority) {
                AmbientTvTheme {
                    ContentRow(row, onItemClick = { selections += it.ratingKey },
                        onItemInfo = { details += it.ratingKey })
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("Home publishes decoded catalog IDs, once per visible game",
                setOf("A SNES", "B PSP"), priority.prioritizedFileIds().toSet())
        }
        val nodes = rule.onAllNodes(hasClickAction())
        assertEquals(2, nodes.fetchSemanticsNodes().size)
        // Each card holds its cover in its own box shape; one row keeps one cover height.
        val covers = row.items.map { item ->
            rule.onNodeWithTag("game-home-cover:${item.ratingKey}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
        }
        assertEquals(1.4f, covers[0].width / covers[0].height, .02f)
        assertEquals(.6f, covers[1].width / covers[1].height, .02f)
        assertEquals(covers[0].height, covers[1].height, .5f)
        val cards = nodes.fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("Both cards share one height", cards[0].height, cards[1].height, .5f)
        nodes[0].performTouchInput { click() }
        nodes[1].performTouchInput { longClick() }
        rule.runOnIdle {
            assertEquals(listOf(row.items[0].ratingKey), selections)
            assertEquals(listOf(row.items[1].ratingKey), details)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun wideHomeCoverReservesBothScreenAndFocusGuttersOnPhone() {
        var gutterPx = 0f
        val row = row(listOf(game("Wide custom cover", "snes", 1600)))
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    gutterPx = with(LocalDensity.current) { screenGutter().toPx() }
                    ContentRow(row, onItemClick = {})
                }
            }
        }
        rule.waitForIdle()
        val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val card = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().single().boundsInRoot
        assertTrue(card.left >= gutterPx)
        assertTrue("Card plus focus growth must fit without horizontal clipping", card.right <= viewport.right - gutterPx)
    }
}
