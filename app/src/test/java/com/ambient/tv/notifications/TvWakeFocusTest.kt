package com.ambient.tv.notifications

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.SidebarActions
import com.ambient.tv.ui.navigation.SidebarContent
import com.ambient.tv.ui.navigation.SidebarUiState
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real TV button focus, Sidebar handoff and Back dispatch around the global wake action.
 * The fixture supplies notice/readiness changes; it does not simulate a MainActivity boot.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h720dp-land-mdpi")
class TvWakeFocusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(contentReady: Boolean, val autoFocus: Boolean) {
        val wakeFocus = TvWakeFocusState()
        val content = FocusRequester()
        val sidebar = FocusRequester()
        var actualContentReady by mutableStateOf(contentReady)
        var sidebarEntryRequested by mutableStateOf(false)
        var wakes by mutableStateOf<List<AppNotice>>(emptyList())
        var sidebarOwnsFocus = false
        var releases = 0
        var pageBacks = 0
        val cancellations = mutableListOf<String>()
        val entryResults = mutableListOf<Boolean>()

        fun requestSidebarFocus(): Boolean {
            sidebarEntryRequested = true
            return runCatching { sidebar.requestFocus() }.getOrDefault(false).also { entered ->
                if (!entered) sidebarEntryRequested = false
            }
        }

        fun cancel(machine: String) {
            cancellations += machine
            wakes = wakes.filterNot { it.key == machine }
        }
    }

    private val sidebarActions = object : SidebarActions {
        override fun prefetch(route: String) = Unit
        override fun refresh(delayMs: Long) = Unit
        override fun applySystemOrder(order: List<String>) = Unit
        override fun renameSystems(names: Map<String, String>, edited: Set<String>) = Unit
        override fun toggleDivider(id: String) = Unit
        override fun applyEntryOrder(systemId: String, order: List<String>) = Unit
        override fun toggleEntryDivider(systemId: String, id: String) = Unit
    }

    private fun wake(machine: String) = AppNotice(
        key = "wake:$machine",
        message = R.string.notice_waking,
        systems = listOf("plex"),
        subject = machine,
        phase = NoticePhase.WORKING,
        kind = NoticeKind.WAKE,
    )

    private fun mount(contentReady: Boolean = true, autoFocus: Boolean = true): Fixture {
        val fixture = Fixture(contentReady, autoFocus)
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    BackHandler { fixture.pageBacks++ }
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            SidebarContent(
                                sidebarState = SidebarUiState(
                                    trailersEnabled = true,
                                    trailersConfigured = true,
                                    filesEnabled = true,
                                ),
                                viewModel = sidebarActions,
                                currentRoute = Destinations.HOME,
                                currentSectionKey = null,
                                onNavigate = {},
                                // A wake action is usable while the selected page is still loading.
                                contentReady = fixture.actualContentReady || fixture.wakeFocus.isAvailable,
                                requestContentFocus = {
                                    val entered = when {
                                        fixture.wakeFocus.hasFocus -> true
                                        !fixture.actualContentReady -> fixture.wakeFocus.requestFocus()
                                        else -> runCatching { fixture.content.requestFocus() }.getOrDefault(false)
                                    }
                                    fixture.entryResults += entered
                                    entered
                                },
                                isEntryRequested = { fixture.sidebarEntryRequested },
                                onEntryRequestConsumed = { fixture.sidebarEntryRequested = false },
                                onFocusOwnershipChanged = { fixture.sidebarOwnsFocus = it },
                                modifier = Modifier.focusRequester(fixture.sidebar),
                            )
                            Box(Modifier.weight(1f).fillMaxSize().padding(40.dp)) {
                                if (fixture.actualContentReady) {
                                    TvActionButton(
                                        text = "Content item",
                                        onClick = {},
                                        modifier = Modifier.focusRequester(fixture.content).testTag("content"),
                                    )
                                }
                            }
                        }
                        if (fixture.wakes.isNotEmpty()) {
                            TvWakeCancelBar(
                                wakes = fixture.wakes,
                                autoFocus = fixture.autoFocus,
                                onCancel = fixture::cancel,
                                onFocusReleased = {
                                    fixture.releases++
                                    runCatching {
                                        if (fixture.actualContentReady) fixture.content.requestFocus()
                                        else fixture.requestSidebarFocus()
                                    }
                                },
                                onWakeNavigateToSidebar = fixture::requestSidebarFocus,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                                wakeFocus = fixture.wakeFocus,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(if (contentReady) fixture.content.requestFocus() else fixture.requestSidebarFocus())
        }
        rule.waitForIdle()
        if (contentReady) rule.onNodeWithTag("content").assertIsFocused()
        else rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        return fixture
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun cancelButtons() = rule.onAllNodesWithText(rule.activity.getString(R.string.home_cancel_wake))

    private fun awaitWakeFocus(fixture: Fixture, index: Int = 0) {
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 2_000) {
            runCatching { cancelButtons()[index].assertIsFocused() }.isSuccess
        }
        rule.runOnIdle { assertTrue(fixture.wakeFocus.hasFocus) }
    }

    @Test fun wakeAppearingAfterContentFocusTakesAndKeepsFocusPastSidebarCollapse() {
        val fixture = mount()
        rule.runOnIdle {
            assertFalse(fixture.wakeFocus.isAvailable)
            assertFalse(fixture.wakeFocus.requestFocus())
            fixture.wakes = listOf(wake("first"))
        }

        awaitWakeFocus(fixture)
        // Sidebar's delayed focus-loss/collapse work runs after 180 ms.
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        cancelButtons()[0].assertIsFocused()
        rule.runOnIdle {
            assertTrue(fixture.wakeFocus.isAvailable)
            assertTrue(fixture.wakeFocus.hasFocus)
            assertFalse(fixture.sidebarOwnsFocus)
            assertTrue(fixture.cancellations.isEmpty())
        }
    }

    @Test fun sidebarRightReachesWakeBeforeContentIsReadyAndDoesNotReclaimItWhenContentArrives() {
        val fixture = mount(contentReady = false, autoFocus = false)
        rule.runOnIdle { fixture.wakes = listOf(wake("pending-page")) }
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        rule.runOnIdle {
            assertTrue(fixture.wakeFocus.isAvailable)
            assertFalse(fixture.wakeFocus.hasFocus)
        }

        press(Key.DirectionRight)
        rule.waitUntil(timeoutMillis = 2_000) { fixture.entryResults.any { it } }
        awaitWakeFocus(fixture)
        rule.runOnIdle { fixture.actualContentReady = true }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        cancelButtons()[0].assertIsFocused()
        rule.runOnIdle {
            assertTrue(fixture.wakeFocus.hasFocus)
            assertFalse(fixture.sidebarOwnsFocus)
        }
    }

    @Test fun wakeLeftArmsSidebarEntryAndRightReturnsToWakeWhileHomeIsStillPending() {
        val fixture = mount(contentReady = false)
        rule.runOnIdle { fixture.wakes = listOf(wake("pending-home")) }
        awaitWakeFocus(fixture)
        rule.runOnIdle {
            assertFalse(fixture.sidebarEntryRequested)
            // The real Sidebar rejects incidental focus entry; Left must explicitly arm it.
            fixture.sidebar.requestFocus()
        }
        cancelButtons()[0].assertIsFocused()

        press(Key.DirectionLeft)
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        rule.runOnIdle {
            assertTrue(fixture.sidebarOwnsFocus)
            assertFalse(fixture.sidebarEntryRequested)
            assertFalse(fixture.wakeFocus.hasFocus)
            assertFalse(fixture.actualContentReady)
        }

        val previousEntries = fixture.entryResults.size
        press(Key.DirectionRight)
        rule.waitUntil(timeoutMillis = 2_000) { fixture.entryResults.drop(previousEntries).any { it } }
        awaitWakeFocus(fixture)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        cancelButtons()[0].assertIsFocused()
        rule.runOnIdle {
            assertFalse(fixture.sidebarOwnsFocus)
            assertFalse(fixture.sidebarEntryRequested)
            assertFalse(fixture.actualContentReady)
            assertTrue(fixture.cancellations.isEmpty())
            assertEquals(0, fixture.releases)
        }
    }

    @Test fun cancellingTheLastWakeArmsSidebarReturnWhenHomeHasNoContentYet() {
        val fixture = mount(contentReady = false)
        rule.runOnIdle { fixture.wakes = listOf(wake("pending-home")) }
        awaitWakeFocus(fixture)

        press(Key.DirectionCenter)
        rule.waitUntil(timeoutMillis = 2_000) { fixture.releases == 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        cancelButtons().assertCountEquals(0)
        rule.runOnIdle {
            assertEquals(listOf("wake:pending-home"), fixture.cancellations)
            assertTrue(fixture.sidebarOwnsFocus)
            assertFalse(fixture.sidebarEntryRequested)
            assertFalse(fixture.actualContentReady)
            assertFalse(fixture.wakeFocus.isAvailable)
            assertFalse(fixture.wakeFocus.hasFocus)
            assertEquals(0, fixture.pageBacks)
        }
    }

    @Test fun progressUpdatesKeepDeliberateContentFocus() {
        val fixture = mount()
        rule.runOnIdle { fixture.wakes = listOf(wake("first")) }
        awaitWakeFocus(fixture)
        rule.runOnIdle { assertTrue(fixture.content.requestFocus()) }
        rule.onNodeWithTag("content").assertIsFocused()

        rule.runOnIdle {
            fixture.wakes = fixture.wakes.map {
                it.copy(subject = "Updated server name", packetsSent = 2, packets = 3)
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("content").assertIsFocused()
        cancelButtons().assertCountEquals(1)
        rule.runOnIdle {
            assertTrue(fixture.wakeFocus.isAvailable)
            assertFalse(fixture.wakeFocus.hasFocus)
            assertEquals(0, fixture.releases)
            assertTrue(fixture.cancellations.isEmpty())
        }
    }

    @Test fun dpadCenterCancelsOnlyTheFocusedMachineAndKeepsTheRemainingWakeReachable() {
        val fixture = mount()
        rule.runOnIdle { fixture.wakes = listOf(wake("first"), wake("second")) }
        awaitWakeFocus(fixture)
        press(Key.DirectionDown)
        cancelButtons()[1].assertIsFocused()
        rule.runOnIdle { assertTrue(fixture.wakeFocus.hasFocus) }

        press(Key.DirectionCenter)
        awaitWakeFocus(fixture)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        cancelButtons().assertCountEquals(1)
        cancelButtons()[0].assertIsFocused()
        rule.runOnIdle {
            assertEquals(listOf("wake:second"), fixture.cancellations)
            assertEquals(listOf("wake:first"), fixture.wakes.map(AppNotice::key))
            assertEquals(0, fixture.releases)
            assertEquals(0, fixture.pageBacks)
        }
    }

    @Test fun backCancelsOneFocusedWakeAtATimeThenReturnsToContent() {
        val fixture = mount()
        rule.runOnIdle { fixture.wakes = listOf(wake("first"), wake("second")) }
        awaitWakeFocus(fixture)

        back()
        awaitWakeFocus(fixture)
        rule.runOnIdle {
            assertEquals(listOf("wake:first"), fixture.cancellations)
            assertEquals(listOf("wake:second"), fixture.wakes.map(AppNotice::key))
            assertEquals(0, fixture.releases)
            assertEquals(0, fixture.pageBacks)
        }
        back()
        rule.waitUntil(timeoutMillis = 2_000) { fixture.releases == 1 }
        rule.onNodeWithTag("content").assertIsFocused()
        cancelButtons().assertCountEquals(0)
        rule.runOnIdle {
            assertEquals(listOf("wake:first", "wake:second"), fixture.cancellations)
            assertFalse(fixture.wakeFocus.isAvailable)
            assertFalse(fixture.wakeFocus.hasFocus)
            assertEquals(0, fixture.pageBacks)
        }
        back()
        rule.runOnIdle { assertEquals(1, fixture.pageBacks) }
    }

    @Test fun completingTheFocusedWakeRestoresContentWithoutCancellingIt() {
        val fixture = mount()
        rule.runOnIdle { fixture.wakes = listOf(wake("first")) }
        awaitWakeFocus(fixture)

        rule.runOnIdle { fixture.wakes = emptyList() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 2_000) { fixture.releases == 1 }
        rule.onNodeWithTag("content").assertIsFocused()
        rule.runOnIdle {
            assertFalse(fixture.wakeFocus.isAvailable)
            assertFalse(fixture.wakeFocus.hasFocus)
            assertFalse(fixture.wakeFocus.requestFocus())
            assertTrue(fixture.cancellations.isEmpty())
        }
    }
}
