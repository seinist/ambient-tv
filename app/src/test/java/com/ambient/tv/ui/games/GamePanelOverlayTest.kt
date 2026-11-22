package com.ambient.tv.ui.games

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.RightSidePanel
import com.ambient.tv.ui.player.PANEL_ENTRANCE_SETTLE_FRAMES
import com.ambient.tv.ui.player.PLAYER_PANEL_ENTER_MS
import com.ambient.tv.ui.player.PLAYER_PANEL_EXIT_MS
import com.ambient.tv.util.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the production game-panel host without loading a native emulator core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1080dp-h720dp-land-mdpi")
class GamePanelOverlayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Level(val panel: String, val category: String? = null, val option: String? = null)
    private var level by mutableStateOf<Level?>(Level("settings"))
    private var pageBack = 0

    private fun mount() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BackHandler { pageBack++ }
            Box(Modifier.fillMaxSize()) {
                val current = level
                if (current != null) GamePanelOverlay(
                    current.panel, current.option, current.category, null, false,
                    onOutsideTap = { level = null },
                ) {
                    RightSidePanel(
                        title = current.option ?: current.category ?: current.panel,
                        modifier = Modifier.align(Alignment.TopEnd),
                        onBack = {
                            level = when {
                                current.option != null -> current.copy(option = null)
                                current.category != null -> current.copy(category = null)
                                current.panel != "settings" -> Level("settings")
                                else -> null
                            }
                        },
                    ) {
                        when {
                            current.panel == "settings" -> {
                                RightPanelChoice("OSD buttons", selected = false,
                                    modifier = Modifier.testTag("open-osd-buttons"),
                                    onClick = { level = Level("osd-buttons") })
                                RightPanelChoice("Core", selected = false,
                                    modifier = Modifier.testTag("open-core"),
                                    onClick = { level = Level("core") })
                            }
                            current.panel == "core" && current.category == null -> {
                                RightPanelChoice("Video", selected = false,
                                    modifier = Modifier.testTag("open-category"),
                                    onClick = { level = current.copy(category = "video") })
                            }
                            current.panel == "core" && current.option == null -> {
                                RightPanelChoice("Renderer", selected = false,
                                    modifier = Modifier.testTag("open-option"),
                                    onClick = { level = current.copy(option = "renderer") })
                            }
                            else -> RightPanelChoice("Available action", selected = false,
                                modifier = Modifier.testTag("leaf-action"), onClick = {})
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        advanceFrame()
        rule.mainClock.advanceTimeBy(PLAYER_PANEL_EXIT_MS.toLong())
        rule.waitForIdle()
        // A completed exit can create the parent only on the next composition frame.
        // Drain Android's pending layout/draw work between levels: the parent's own
        // onPlaced must open PanelEntranceGate before its entrance can advance.
        advanceFrame()
        // The production gate waits for a drawn frame after placement; Animatable then
        // needs its first frame to establish the animation's start time.
        repeat(PANEL_ENTRANCE_SETTLE_FRAMES + 1) { advanceFrame() }
        rule.mainClock.advanceTimeBy(PLAYER_PANEL_ENTER_MS.toLong())
        rule.waitForIdle()
    }

    private fun advanceFrame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun click(tag: String, expected: Level) {
        rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        settle()
        rule.runOnIdle { assertEquals("Click on $tag must open its panel", expected, level) }
    }

    private fun back() {
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    @Test fun osdButtonsBackRestoresInteractiveSettingsAndCanCloseAgain() {
        mount()
        click("open-osd-buttons", Level("osd-buttons"))
        back()
        rule.runOnIdle { assertEquals(Level("settings"), level) }
        // An unkeyed host retained the child's closing latch at progress zero. Its parent
        // could neither reopen a child nor receive another Back.
        click("open-osd-buttons", Level("osd-buttons"))
        back()
        back()
        rule.runOnIdle {
            assertNull(level)
            assertEquals(0, pageBack)
        }
    }

    @Test fun nestedCoreOptionAndCategoryEachReturnToAUsableParent() {
        mount()
        click("open-core", Level("core"))
        click("open-category", Level("core", category = "video"))
        click("open-option", Level("core", category = "video", option = "renderer"))
        back()
        rule.onNodeWithTag("open-option").assertIsDisplayed()
        rule.runOnIdle { assertEquals(Level("core", category = "video"), level) }
        back()
        rule.onNodeWithTag("open-category").assertIsDisplayed()
        rule.runOnIdle { assertEquals(Level("core"), level) }
        back()
        rule.onNodeWithTag("open-core").assertIsDisplayed()
        back()
        rule.runOnIdle {
            assertNull(level)
            assertEquals(0, pageBack)
        }
    }
}
