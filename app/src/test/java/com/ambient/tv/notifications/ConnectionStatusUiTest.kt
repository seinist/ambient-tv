package com.ambient.tv.notifications

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Narrow German phone layout and touch semantics; no service/network mocking is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "de-rDE-w320dp-h680dp-port-mdpi")
class ConnectionStatusUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun wake() = AppNotice(
        key = "wake:medienserver", message = R.string.notice_waking,
        subject = "Medienserver mit langem Namen", systems = listOf("plex", "tvheadend"),
        phase = NoticePhase.WORKING, kind = NoticeKind.WAKE,
        progressStartedAt = System.currentTimeMillis(), progressTotalMs = 60_000,
        packetsSent = 1, packets = 3, nextPacketAt = System.currentTimeMillis() + 20_000,
    )
    private fun status(notice: AppNotice = wake()) = ConnectivitySnapshot(
        sessionId = 1, visible = true, presented = true,
        entries = listOf(NoticeEntry(1, notice, 0, 0, 0)),
    )

    @Test fun narrowPhoneKeepsBothActionsInsideCardAndHidesTechnicalNoise() {
        val current = status()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        CompactConnectionStatus(current, reduced = true, onDetails = {}, onCancel = {})
                    }
                }
            }
        }
        val detailsButton = rule.onNodeWithTag("connection-status-details")
            .assertContentDescriptionEquals(rule.activity.getString(R.string.start_status_details))
        val cancelButton = rule.onNodeWithTag("connection-status-cancel")
            .assertContentDescriptionEquals(rule.activity.getString(R.string.home_cancel_wake))
        val details = detailsButton.getBoundsInRoot()
        val cancel = cancelButton.getBoundsInRoot()
        val card = rule.onNodeWithTag("connection-status-card").getBoundsInRoot()
        assertTrue(details.left >= card.left && cancel.right <= card.right)
        assertTrue(details.right <= cancel.left)
        // Both icons share the summary's row; there is no second line of full-width buttons.
        assertTrue(details.top < cancel.bottom && cancel.top < details.bottom)
        assertTrue(card.bottom - card.top < 100.dp)
        rule.onNodeWithText(rule.activity.getString(R.string.home_cancel_wake)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.start_status_packets, 1, 3)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.start_status_technical_details)).assertDoesNotExist()
    }


    @Test fun inlineIconActionsOpenDetailsOrCancelOnlyWhenExplicitlySelected() {
        val current = status()
        var detailsOpens = 0
        val cancelled = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        CompactConnectionStatus(
                            current, reduced = true,
                            onDetails = { detailsOpens++ }, onCancel = { cancelled += it.key },
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("connection-status-details").performClick()
        rule.runOnIdle {
            assertEquals(1, detailsOpens)
            assertTrue(cancelled.isEmpty())
        }
        rule.onNodeWithTag("connection-status-cancel").performClick()
        rule.runOnIdle {
            assertEquals(1, detailsOpens)
            assertEquals(listOf("wake:medienserver"), cancelled)
        }
    }

    @Test fun multipleActiveWakesKeepCancellationInsideTheDeviceDetails() {
        val first = wake()
        val second = wake().copy(key = "wake:backup", subject = "Backup-Server")
        val current = ConnectivitySnapshot(
            sessionId = 1, visible = true, presented = true,
            entries = listOf(NoticeEntry(1, first, 0, 0, 0), NoticeEntry(2, second, 0, 0, 0)),
        )
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        CompactConnectionStatus(current, reduced = true, onDetails = {}, onCancel = {})
                    }
                }
            }
        }
        rule.onNodeWithTag("connection-status-details").assertIsDisplayed()
        rule.onNodeWithTag("connection-status-cancel").assertDoesNotExist()
    }

    @Test fun swipingMinimizesWithoutCancellingAndBadgeReopensStatus() {
        val current = status()
        var minimized by mutableStateOf(false)
        var cancellations = 0
        var opened = 0
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (minimized) {
                            ConnectionStatusBadge(false, { opened++ })
                        } else {
                            MinimizableConnectionStatus(
                                onMinimize = { minimized = true }, modifier = Modifier.testTag("minimizable-status"),
                            ) {
                                CompactConnectionStatus(current, reduced = true, onDetails = { opened++ }, onCancel = { cancellations++ })
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("minimizable-status").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("connection-status-badge").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertTrue(minimized)
            assertEquals(0, cancellations)
            assertEquals(1, opened)
        }
    }

    @Test fun technicalPacketsAppearOnlyAfterExpansionAndCancelTargetsTheMachine() {
        val current = status()
        val row = current.rows.single()
        val cancelled = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ConnectionStatusDetailRow(row, reduced = true, onCancel = { cancelled += it.key }, onRetry = {}, canRetry = { false })
                    }
                }
            }
        }
        val packets = rule.activity.getString(R.string.start_status_packets, 1, 3)
        rule.onNodeWithText(packets).assertDoesNotExist()
        rule.onNodeWithTag("connection-technical:" + row.key).performClick()
        rule.onNodeWithText(packets).assertIsDisplayed()
        rule.onNodeWithTag("connection-cancel:" + row.key).performClick()
        rule.runOnIdle { assertEquals(listOf("wake:medienserver"), cancelled) }
    }

    @Test fun cancelledAttemptOffersExplicitRetry() {
        val notice = wake().copy(phase = NoticePhase.INFO, cancelled = true)
        val row = status(notice).rows.single()
        var retries = 0
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ConnectionStatusDetailRow(row, reduced = true, onCancel = {}, onRetry = { retries++ }, canRetry = { true })
                    }
                }
            }
        }
        rule.onNodeWithTag("connection-retry:" + row.key).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun independentErrorDoesNotDisableCancellationOfTheSameMachinesLiveWake() {
        val waking = wake()
        val failedService = AppNotice(
            key = "connection:medienserver", message = R.string.notice_unreachable,
            systems = listOf("plex"), subject = "Medienserver",
            phase = NoticePhase.ERROR, kind = NoticeKind.CONNECTION,
        )
        val current = ConnectivitySnapshot(
            sessionId = 1,
            entries = listOf(
                NoticeEntry(1, waking, 0, 0, 0),
                NoticeEntry(2, failedService, 0, 0, 0),
            ),
        )
        val row = current.rows.single()
        assertEquals(NoticePhase.ERROR, row.notice.phase)
        var cancelled: AppNotice? = null
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ConnectionStatusDetailRow(row, reduced = true, onCancel = { cancelled = it }, onRetry = {}, canRetry = { false })
                    }
                }
            }
        }
        rule.onNodeWithTag("connection-cancel:" + row.key).performClick()
        rule.runOnIdle {
            assertEquals("wake:medienserver", cancelled?.key)
            assertEquals(NoticePhase.WORKING, cancelled?.phase)
            assertEquals(NoticeKind.WAKE, cancelled?.kind)
        }
    }

    @Test fun introUsesOnlyCompactSummaryWithoutActionsOrPacketCounters() {
        var current by mutableStateOf(status())
        rule.setContent {
            CompositionLocalProvider(LocalHandheldUi provides true) {
                AmbientTvTheme {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        IntroConnectionStatus(current, Modifier.testTag("intro-status"))
                    }
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.start_status_waking_server)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.home_cancel_wake)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.start_status_packets, 1, 3)).assertDoesNotExist()
        val bounds = rule.onNodeWithTag("intro-status").getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top < 120.dp)
        rule.runOnIdle {
            current = status(wake().copy(
                key = "connection:medienserver",
                kind = NoticeKind.CONNECTION,
                message = R.string.notice_service_starting,
            ))
        }
        rule.onNodeWithText(rule.activity.getString(R.string.start_status_starting_services)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.start_status_connecting)).assertDoesNotExist()
    }
}
