package com.ambient.tv.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

    @Test fun severityAndSourceFiltersKeepListUsable() {
        showViewer()
        rule.onNodeWithTag("diagnostics-entry:${warning.key}").assertExists()
        rule.onNodeWithTag("diagnostics-entry:${information.key}").assertExists()

        rule.onNodeWithTag("diagnostics-severity-ERRORS").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("diagnostics-entry:${recent.key}").assertExists()
        rule.onNodeWithTag("diagnostics-entry:${warning.key}").assertDoesNotExist()
        rule.onNodeWithTag("diagnostics-entry:${information.key}").assertDoesNotExist()

        rule.onNodeWithTag("diagnostics-severity-ALL").performClick()
        rule.onNodeWithTag("diagnostics-source-mpv").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("diagnostics-entry:${warning.key}").assertExists()
        rule.onNodeWithTag("diagnostics-entry:${recent.key}").assertDoesNotExist()
    }

    @Test fun dismissingDetailRestoresFocusToTheExactEntry() {
        showViewer()
        val entry = "diagnostics-entry:${recent.key}"
        rule.onNodeWithTag(entry).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("diagnostics-copy").assertExists()
        rule.onNodeWithTag("diagnostics-detail-back").performClick()
        // The shared dialog uses a native 160 ms exit animation.
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag(entry).assertIsFocused() }.isSuccess
        }
        rule.onNodeWithTag(entry).assertIsFocused()
        rule.onNodeWithTag("diagnostics-close").assertExists()
    }
}
