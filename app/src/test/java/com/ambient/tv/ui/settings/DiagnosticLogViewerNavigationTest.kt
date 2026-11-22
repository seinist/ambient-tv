package com.ambient.tv.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import com.ambient.tv.diagnostics.DiagnosticLevel
import com.ambient.tv.diagnostics.DiagnosticViewerEntry
import com.ambient.tv.diagnostics.DiagnosticViewerSnapshot
import com.ambient.tv.ui.theme.AmbientTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercise the real dialog, chips and shared lazy-list controls. Synthetic snapshot data keeps
 * these tests independent from crash handlers, disk IO, external players and Android logcat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w1200dp-h720dp-land-mdpi")
class DiagnosticLogViewerNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val recent = DiagnosticViewerEntry(
        sequence = 3L,
        timestamp = "2026-11-20 14:38:03.000",
        priority = DiagnosticLevel.ERROR,
        tag = "smarttube",
        thread = "io",
        message = "HTTP 403",
    )
    private val warning = DiagnosticViewerEntry(
        sequence = 2L,
        timestamp = "2026-11-20 14:38:02.000",
        priority = DiagnosticLevel.WARN,
        tag = "mpv",
        thread = "player",
        message = "Seek failed",
    )
    private val information = DiagnosticViewerEntry(
        sequence = 1L,
        timestamp = "2026-11-20 14:38:01.000",
        priority = DiagnosticLevel.INFO,
        tag = "plex",
        thread = "worker",
        message = "Connected",
    )

    private fun showViewer() {
        val snapshot = DiagnosticViewerSnapshot("sample", listOf(recent, warning, information))
        val reader: (String?) -> DiagnosticViewerSnapshot? = { old -> if (old == null) snapshot else null }
        rule.setContent {
            AmbientTvTheme {
                DiagnosticLogViewer(
                    loggingEnabled = true,
                    onExport = {},
                    onDismiss = {},
                    readSnapshot = reader,
                )
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("diagnostics-entry:${recent.key}").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    /** TV Surface semantics are reliably activated through the remote key path. */
    private fun pressRemoteCenter(tag: String) {
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    @Test fun severityAndSourceFiltersKeepListUsable() {
        showViewer()
        rule.onNodeWithTag("diagnostics-entry:${warning.key}").assertExists()
        // LazyColumn detaches offscreen events; the persistent counter covers the full log.
        rule.onNodeWithText("3 of 3", substring = true).assertExists()

        pressRemoteCenter("diagnostics-severity-ERRORS")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("diagnostics-count").fetchSemanticsNodes()
                .any { node -> node.config.toString().contains("1 of 3") }
        }
        rule.onNodeWithText("1 of 3", substring = true).assertExists()
        rule.onNodeWithTag("diagnostics-entry:${recent.key}").assertExists()
        rule.onNodeWithTag("diagnostics-entry:${warning.key}").assertDoesNotExist()
        rule.onNodeWithTag("diagnostics-entry:${information.key}").assertDoesNotExist()

        pressRemoteCenter("diagnostics-severity-ALL")
        pressRemoteCenter("diagnostics-source-mpv")
        rule.onNodeWithText("1 of 3", substring = true).assertExists()
        rule.onNodeWithTag("diagnostics-entry:${warning.key}").assertExists()
        rule.onNodeWithTag("diagnostics-entry:${recent.key}").assertDoesNotExist()
    }

    @Test fun dpadMovesBetweenLogEntryAndSourceFiltersWithoutTrappingFocus() {
        showViewer()
        val entry = "diagnostics-entry:${recent.key}"
        rule.onNodeWithTag(entry).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag(entry).assertIsFocused()
        rule.onNodeWithTag(entry).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()

        // A list row must be able to reach filters directly with Up, not wrap indefinitely
        // among log entries or jump out to the dialog/underlying Settings page.
        val filterTags = listOf("diagnostics-source-mpv", "diagnostics-source-plex", "diagnostics-source-smarttube")
        val focused = filterTags.firstOrNull { tag ->
            runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess
        }
        org.junit.Assert.assertNotNull("Up from first log entry must reach a source filter", focused)

        rule.onNodeWithTag(checkNotNull(focused)).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(entry).assertIsFocused()
    }

    @Test fun dismissingDetailRestoresFocusToTheExactEntry() {
        showViewer()
        val entry = "diagnostics-entry:${recent.key}"
        pressRemoteCenter(entry)
        // The details replace list content in the same dialog, avoiding a second window.
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("diagnostics-copy").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("diagnostics-copy").assertExists()
        pressRemoteCenter("diagnostics-detail-back")
        // Back returns to the list without dismissing the owning dialog window.
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag(entry).assertIsFocused() }.isSuccess
        }
        rule.onNodeWithTag(entry).assertIsFocused()
        rule.onNodeWithTag("diagnostics-close").assertExists()
    }
}
