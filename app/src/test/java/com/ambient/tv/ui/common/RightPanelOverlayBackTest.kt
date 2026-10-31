package com.ambient.tv.ui.common

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ambient.tv.ui.player.PLAYER_PANEL_ENTER_MS
import com.ambient.tv.ui.player.PLAYER_PANEL_EXIT_MS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Back must be owned by the *visible* right drawer, not the browse grid behind it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RightPanelOverlayBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun backWaitsForPanelExitAndNeverNavigatesUnderlyingPage() {
        val drawerClosed = AtomicInteger(0)
        val pageBack = AtomicInteger(0)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var open by remember { mutableStateOf(true) }
            BackHandler(enabled = !open) { pageBack.incrementAndGet() }
            Box(Modifier.fillMaxSize()) {
                if (open) RightPanelOverlay {
                    RightSidePanel(
                        title = "Ansicht",
                        onBack = { drawerClosed.incrementAndGet(); open = false },
                        requestHeaderFocus = false,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        RightPanelInfoRow("Layout")
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(PLAYER_PANEL_ENTER_MS + 80)
        rule.waitForIdle()
        rule.runOnIdle {
            rule.activity.onBackPressedDispatcher.onBackPressed()
            rule.activity.onBackPressedDispatcher.onBackPressed()
            // The old duplicate BackHandler dismissed immediately and exposed the page.
            assertEquals(0, drawerClosed.get())
            assertEquals(0, pageBack.get())
        }
        rule.mainClock.advanceTimeBy(PLAYER_PANEL_EXIT_MS + 80)
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, drawerClosed.get())
            assertEquals(0, pageBack.get())
        }
    }
}
