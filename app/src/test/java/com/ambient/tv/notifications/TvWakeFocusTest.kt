package com.ambient.tv.notifications

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PerformanceProfile
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.navigation.SidebarActions
import com.ambient.tv.ui.navigation.SidebarContent
import com.ambient.tv.ui.navigation.SidebarUiState
import com.ambient.tv.ui.theme.AmbientTvTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production summary/panel with real Sidebar, remote keys, Back and focus hand-back. */
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
        var notices by mutableStateOf<List<AppNotice>>(emptyList())
        var panel by mutableStateOf(false)
        var releases = 0
        var pageBacks = 0
        val cancellations = mutableListOf<String>()
        fun requestSidebar(): Boolean {
            sidebarEntryRequested = true
            return runCatching { sidebar.requestFocus() }.getOrDefault(false).also {
                if (!it) sidebarEntryRequested = false
            }
        }
        fun release() {
            if (panel) return
            releases++
            if (actualContentReady) runCatching { content.requestFocus() } else requestSidebar()
        }
        fun cancel(notice: AppNotice) {
            cancellations += notice.key
            notices = notices.filterNot { it.key == notice.key }
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
        key = "wake:$machine", message = R.string.notice_waking, systems = listOf("plex"),
        subject = machine, phase = NoticePhase.WORKING, kind = NoticeKind.WAKE,
    )
    private fun status(notices: List<AppNotice>) = ConnectivitySnapshot(
        sessionId = 1, visible = true, presented = true,
        entries = notices.mapIndexed { index, notice -> NoticeEntry(index.toLong(), notice, 0, 0, 0) },
    )

    private fun mount(contentReady: Boolean = true, autoFocus: Boolean = false): Fixture {
        val fixture = Fixture(contentReady, autoFocus)
        rule.setContent {
            val scope = rememberCoroutineScope()
            val current = status(fixture.notices)
            CompositionLocalProvider(LocalHandheldUi provides false) {
                AmbientTvTheme {
                    BackHandler { fixture.pageBacks++ }
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            SidebarContent(
                                sidebarState = SidebarUiState(trailersEnabled = true, trailersConfigured = true),
                                viewModel = sidebarActions,
                                currentRoute = Destinations.HOME,
                                currentSectionKey = null,
                                onNavigate = {},
                                contentReady = fixture.actualContentReady || fixture.wakeFocus.isAvailable,
                                requestContentFocus = {
                                    when {
                                        fixture.wakeFocus.hasFocus -> true
                                        !fixture.actualContentReady -> fixture.wakeFocus.requestFocus()
                                        else -> runCatching { fixture.content.requestFocus() }.getOrDefault(false)
                                    }
                                },
                                isEntryRequested = { fixture.sidebarEntryRequested },
                                onEntryRequestConsumed = { fixture.sidebarEntryRequested = false },
                                modifier = Modifier.focusRequester(fixture.sidebar),
                            )
                            Box(Modifier.weight(1f).fillMaxSize().padding(40.dp)) {
                                if (fixture.actualContentReady) TvActionButton(
                                    "Content item", {}, Modifier.focusRequester(fixture.content).testTag("content"),
                                )
                            }
                        }
                        if (fixture.notices.isNotEmpty() && !fixture.panel) CompactConnectionStatus(
                            status = current,
                            reduced = true,
                            onDetails = { fixture.panel = true },
                            onCancel = fixture::cancel,
                            autoFocus = fixture.autoFocus,
                            wakeFocus = fixture.wakeFocus,
                            onNavigateToSidebar = fixture::requestSidebar,
                            onFocusReleased = fixture::release,
                            modifier = Modifier.align(Alignment.TopEnd).width(420.dp).padding(32.dp),
                        )
                        if (fixture.panel) ConnectionStatusDetails(
                            current, reduced = true,
                            onClose = {
                                fixture.panel = false
                                scope.launch {
                                    repeat(8) {
                                        withFrameNanos { }
                                        if (fixture.wakeFocus.requestFocus()) return@launch
                                    }
                                    fixture.release()
                                }
                            },
                            onCancel = fixture::cancel, onRetry = {}, canRetry = { false },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(if (contentReady) fixture.content.requestFocus() else fixture.requestSidebar()) }
        rule.waitForIdle()
        return fixture
    }
    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }
    private fun awaitDetailsFocus(fixture: Fixture) {
        rule.waitUntil(timeoutMillis = 2_000) {
            runCatching { rule.onNodeWithTag("connection-status-details").assertIsFocused() }.isSuccess
        }
        rule.runOnIdle { assertTrue(fixture.wakeFocus.hasFocus) }
    }

    @Test fun statusAndProgressDoNotStealExistingContentFocus() {
        val fixture = mount()
        rule.runOnIdle { fixture.notices = listOf(wake("first")) }
        rule.waitForIdle()
        rule.onNodeWithTag("content").assertIsFocused()
        rule.runOnIdle { fixture.notices = fixture.notices.map { it.copy(packetsSent = 2, packets = 3) } }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("content").assertIsFocused()
        rule.onAllNodesWithTag("connection-status-card").assertCountEquals(1)
        rule.runOnIdle { assertTrue(fixture.cancellations.isEmpty()) }
    }

    @Test fun sidebarRightReachesDetailsWhileHomeIsPendingAndBackOnlyLeavesStatus() {
        val fixture = mount(contentReady = false)
        rule.runOnIdle { fixture.notices = listOf(wake("first")) }
        rule.waitForIdle()
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight)
        awaitDetailsFocus(fixture)
        back()
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        rule.runOnIdle { assertTrue(fixture.cancellations.isEmpty()); assertEquals(0, fixture.pageBacks) }
        press(Key.DirectionRight)
        awaitDetailsFocus(fixture)
    }

    @Test fun waitingStartupFocusesDetailsAndCancellationRequiresExplicitButton() {
        val fixture = mount(contentReady = false, autoFocus = true)
        rule.runOnIdle { fixture.notices = listOf(wake("first")) }
        awaitDetailsFocus(fixture)
        press(Key.DirectionRight)
        rule.onNodeWithTag("connection-status-cancel").assertIsFocused()
        press(Key.DirectionCenter)
        rule.waitUntil(timeoutMillis = 2_000) { fixture.releases > 0 }
        rule.runOnIdle { assertEquals("A removed card must return focus exactly once", 1, fixture.releases) }
        rule.onNodeWithTag("sidebar:home", useUnmergedTree = true).assertIsFocused()
        rule.runOnIdle { assertEquals(listOf("wake:first"), fixture.cancellations); assertEquals(0, fixture.pageBacks) }
    }

    @Test fun multipleWakesUseOneSummaryAndDetailsCancelOnlyTheChosenMachine() {
        val fixture = mount(contentReady = false, autoFocus = true)
        rule.runOnIdle { fixture.notices = listOf(wake("first"), wake("second")) }
        awaitDetailsFocus(fixture)
        rule.onAllNodesWithTag("connection-status-card").assertCountEquals(1)
        rule.onAllNodesWithTag("connection-status-cancel").assertCountEquals(0)
        press(Key.DirectionCenter)
        rule.onNodeWithTag("connection-status-panel").assertIsDisplayed()
        rule.onNodeWithTag("connection-cancel:machine:first").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("connection-cancel:machine:second").assertIsFocused()
        // TV Surface accepts a remote confirmation; performClick would inject a touch.
        press(Key.DirectionCenter)
        rule.runOnIdle {
            assertEquals(listOf("wake:second"), fixture.cancellations)
            assertEquals(listOf("wake:first"), fixture.notices.map(AppNotice::key))
        }
        rule.onNodeWithTag("connection-cancel:machine:first").assertIsDisplayed()
        back()
        awaitDetailsFocus(fixture)
        rule.runOnIdle { assertFalse(fixture.panel); assertEquals(0, fixture.pageBacks) }
    }

    @Test fun finishingWhileCancelIsFocusedReturnsToDetailsWithoutCancelling() {
        val fixture = mount(contentReady = false, autoFocus = true)
        rule.runOnIdle { fixture.notices = listOf(wake("first")) }
        awaitDetailsFocus(fixture)
        press(Key.DirectionRight)
        rule.onNodeWithTag("connection-status-cancel").assertIsFocused()
        rule.runOnIdle { fixture.notices = fixture.notices.map { it.copy(phase = NoticePhase.SUCCESS, message = R.string.notice_awake) } }
        awaitDetailsFocus(fixture)
        rule.onAllNodesWithTag("connection-status-cancel").assertCountEquals(0)
        rule.runOnIdle { assertTrue(fixture.cancellations.isEmpty()) }
    }

    @Test fun disappearingFocusedStatusReturnsToContent() {
        val fixture = mount(autoFocus = true)
        rule.runOnIdle { fixture.notices = listOf(wake("first")) }
        awaitDetailsFocus(fixture)
        rule.runOnIdle { fixture.notices = emptyList() }
        // Apply removal and drain its Android View.post before checking the hand-back.
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 2_000) { fixture.releases > 0 }
        rule.runOnIdle { assertEquals("A removed card must return focus exactly once", 1, fixture.releases) }
        rule.onNodeWithTag("content").assertIsFocused()
        rule.runOnIdle { assertFalse(fixture.wakeFocus.isAvailable); assertTrue(fixture.cancellations.isEmpty()) }
    }

    @Test fun completionWhileDetailsIsOpenClosesBackToUsableContent() {
        val fixture = mount(autoFocus = true)
        rule.runOnIdle { fixture.notices = listOf(wake("first")) }
        awaitDetailsFocus(fixture)
        press(Key.DirectionCenter)
        rule.onNodeWithTag("connection-status-panel").assertIsDisplayed()
        rule.runOnIdle { fixture.notices = emptyList() }
        rule.waitForIdle()
        rule.onNodeWithTag("connection-status-panel").assertIsDisplayed()
        back()
        rule.waitUntil(timeoutMillis = 2_000) { fixture.releases == 1 }
        rule.onNodeWithTag("content").assertIsFocused()
        rule.runOnIdle {
            assertFalse(fixture.panel)
            assertTrue(fixture.cancellations.isEmpty())
            assertEquals(0, fixture.pageBacks)
        }
    }

    @Test fun notificationsOffStillProvidesReachableDetailsAndExplicitCancel() {
        val preferences = rule.activity.getSharedPreferences("ambient_settings", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString(AppPreferences.KEY_NOTIFICATION_DISPLAY_DURATION, "normal")
        preferences.edit().putString(AppPreferences.KEY_NOTIFICATION_DISPLAY_DURATION, "off").commit()
        val focus = TvWakeFocusState()
        val cancelled = mutableListOf<String>()
        val notice = wake("notifications-off")
        try {
            rule.setContent {
                CompositionLocalProvider(
                    LocalHandheldUi provides false,
                    LocalPerformanceProfile provides PerformanceProfile.resolve(PerformanceMode.LOW, false),
                ) {
                    AmbientTvTheme {
                        AppNotificationHost(
                            wakeFocus = focus,
                            onCancelConnection = { cancelled += it.key },
                            onRetryConnection = {},
                            canRetryConnection = { false },
                        )
                    }
                }
            }
            rule.runOnIdle { AppNotifications.store.post(notice) }
            rule.waitForIdle()
            rule.onNodeWithTag("connection-status-badge").assertIsDisplayed()
            rule.runOnIdle { assertTrue(focus.requestFocus()) }
            press(Key.DirectionCenter)
            rule.onNodeWithTag("connection-status-panel").assertIsDisplayed()
            back()
            rule.runOnIdle { assertTrue(cancelled.isEmpty()) }
            rule.onNodeWithTag("connection-status-badge").assertIsFocused()
            press(Key.DirectionCenter)
            rule.onNodeWithTag("connection-cancel:machine:notifications-off").assertIsFocused()
            press(Key.DirectionCenter)
            rule.runOnIdle { assertEquals(listOf("wake:notifications-off"), cancelled) }
        } finally {
            AppNotifications.store.removeConnectivity(notice.key)
            preferences.edit().putString(AppPreferences.KEY_NOTIFICATION_DISPLAY_DURATION, previous).commit()
        }
    }
}
