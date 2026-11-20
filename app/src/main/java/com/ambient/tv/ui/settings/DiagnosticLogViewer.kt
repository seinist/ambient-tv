package com.ambient.tv.ui.settings

import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.diagnostics.DiagnosticLevel
import com.ambient.tv.diagnostics.DiagnosticLog
import com.ambient.tv.diagnostics.DiagnosticViewerEntry
import com.ambient.tv.diagnostics.DiagnosticViewerSnapshot
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.common.TvTextField
import com.ambient.tv.ui.common.TvLazyColumn
import com.ambient.tv.ui.common.overlayLazyScrollbar
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LogViewerSeverity {
    ALL, ERRORS, WARNINGS, INFORMATION;

    fun includes(priority: Int): Boolean = when (this) {
        ALL -> true
        ERRORS -> priority >= DiagnosticLevel.ERROR
        WARNINGS -> priority == DiagnosticLevel.WARN
        INFORMATION -> priority < DiagnosticLevel.WARN
    }
}

/**
 * TV/phone diagnostic viewer. No new logger or persistent state is created here.
 * File access and parsing run only while the viewer is open and always on Dispatchers.IO.
 */
@Composable
internal fun DiagnosticLogViewer(
    loggingEnabled: Boolean,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
    readSnapshot: (String?) -> DiagnosticViewerSnapshot? = DiagnosticLog::viewerSnapshot,
) {
    var entries by remember { mutableStateOf<List<DiagnosticViewerEntry>>(emptyList()) }
    var pendingEntries by remember { mutableStateOf<List<DiagnosticViewerEntry>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf(LogViewerSeverity.ALL) }
    var sourceTag by remember { mutableStateOf<String?>(null) }
    var openedEntry by remember { mutableStateOf<DiagnosticViewerEntry?>(null) }
    var returnToEntryKey by remember { mutableStateOf<String?>(null) }
    val returnToEntryFocus = remember { FocusRequester() }
    var focusedEntryKey by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val handheld = LocalHandheldUi.current

    // Version checks are metadata-only; up to 3 MiB are parsed only when the files change.
    // Freeze an actively browsed list to avoid new events pushing away DPAD focus.
    LaunchedEffect(refreshGeneration) {
        var lastRevision: String? = null
        var firstRead = true
        while (isActive) {
            val snapshot = withContext(Dispatchers.IO) { readSnapshot(lastRevision) }
            if (snapshot != null) {
                lastRevision = snapshot.revision
                if (firstRead || entries.isEmpty() ||
                    (scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0 &&
                        focusedEntryKey == null && openedEntry == null)
                ) {
                    entries = snapshot.entries
                    pendingEntries = null
                } else {
                    pendingEntries = snapshot.entries
                }
            }
            firstRead = false
            loaded = true
            delay(2_500)
        }
    }

    val tags = remember(entries) { entries.map { it.tag }.distinct().sorted() }
    val filtered = remember(entries, query, severity, sourceTag) {
        val needle = query.trim()
        entries.filter { entry ->
            severity.includes(entry.priority) &&
                (sourceTag == null || sourceTag == entry.tag) &&
                (needle.isEmpty() ||
                    entry.tag.contains(needle, ignoreCase = true) ||
                    entry.thread.contains(needle, ignoreCase = true) ||
                    entry.message.contains(needle, ignoreCase = true) ||
                    entry.timestamp.contains(needle, ignoreCase = true))
        }
    }


    // Filters are regular list stages rather than permanent headers. On a landscape phone the
    // keyboard and dialog footer would otherwise leave no room for log entries. The same
    // TV-aware lazy list handles vertical navigation, wrapping and its optional scrollbar.
    LaunchedEffect(tags) {
        if (sourceTag != null && sourceTag !in tags) sourceTag = null
    }
    LaunchedEffect(openedEntry, returnToEntryKey) {
        if (openedEntry == null && returnToEntryKey != null) {
            withFrameNanos { }
            runCatching { returnToEntryFocus.requestFocus() }
            returnToEntryKey = null
        }
    }

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.diagnostics_viewer_title),
        hint = stringResource(if (loggingEnabled) R.string.diagnostics_viewer_hint else R.string.diagnostics_viewer_disabled),
        fillHeight = true,
        scrollable = false,
        widthFraction = if (handheld) null else 0.84f,
        maxWidth = 1120.dp,
        maxHeightFraction = 0.94f,
        footer = {
            TvActionButton(
                text = stringResource(R.string.diagnostics_viewer_refresh),
                modifier = Modifier.testTag("diagnostics-refresh"),
                onClick = {
                    pendingEntries?.let { entries = it }
                    pendingEntries = null
                    refreshGeneration++
                    scope.launch { scroll.scrollToItem(0) }
                },
            )
            if (!handheld) TvActionButton(text = stringResource(R.string.diagnostics_viewer_export), onClick = onExport)
            TvActionButton(text = stringResource(R.string.back), modifier = Modifier.testTag("diagnostics-close"), onClick = onDismiss)
        },
    ) {
        TvLazyColumn(
            modifier = Modifier.fillMaxSize().testTag("diagnostics-list").overlayLazyScrollbar(scroll),
            state = scroll,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item(key = "diagnostics-search") {
                TvTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    label = stringResource(R.string.diagnostics_viewer_search),
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics-search"),
                )
            }
            item(key = "diagnostics-severity") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics-severity").focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(LogViewerSeverity.entries, key = { it.name }) { option ->
                        val title = stringResource(when (option) {
                            LogViewerSeverity.ALL -> R.string.diagnostics_viewer_all
                            LogViewerSeverity.ERRORS -> R.string.diagnostics_viewer_errors
                            LogViewerSeverity.WARNINGS -> R.string.diagnostics_viewer_warnings
                            LogViewerSeverity.INFORMATION -> R.string.diagnostics_viewer_info
                        })
                        TvChip(text = title, selected = severity == option, onClick = { severity = option })
                    }
                }
            }
            if (tags.isNotEmpty()) item(key = "diagnostics-sources") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().testTag("diagnostics-sources").focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all-tags") {
                        TvChip(
                            text = stringResource(R.string.diagnostics_viewer_all_sources),
                            selected = sourceTag == null,
                            onClick = { sourceTag = null },
                        )
                    }
                    items(tags, key = { it }) { tag ->
                        TvChip(text = tag, selected = sourceTag == tag, onClick = { sourceTag = tag })
                    }
                }
            }
            if (pendingEntries != null) item(key = "diagnostics-pending") {
                Text(
                    stringResource(R.string.diagnostics_viewer_updates_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (handheld) item(key = "diagnostics-export") {
                TvActionButton(
                    text = stringResource(R.string.diagnostics_viewer_export),
                    onClick = onExport,
                    fillWidth = true,
                )
            }
            item(key = "diagnostics-count") {
                Text(
                    stringResource(R.string.diagnostics_viewer_count, filtered.size, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                !loaded -> item(key = "diagnostics-loading") {
                    Text(stringResource(R.string.diagnostics_viewer_loading))
                }
                filtered.isEmpty() -> item(key = "diagnostics-empty") {
                    Text(
                        stringResource(if (entries.isEmpty()) R.string.diagnostics_viewer_empty else R.string.diagnostics_viewer_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(filtered, key = { it.key }) { entry ->
                    TvActionButton(
                        text = "${entry.timestamp.substringAfter(' ')}  ${entry.levelLetter}  ${entry.tag} · ${entry.headline.take(110)}",
                        fillWidth = true,
                        modifier = Modifier
                            .then(if (entry.key == returnToEntryKey) Modifier.focusRequester(returnToEntryFocus) else Modifier)
                            .testTag("diagnostics-entry:${entry.key}")
                            .onFocusChanged { focus ->
                                if (focus.isFocused) focusedEntryKey = entry.key
                                else if (focusedEntryKey == entry.key) focusedEntryKey = null
                            },
                        onClick = {
                            returnToEntryKey = entry.key
                            openedEntry = entry
                        },
                    )
                }
            }
        }
    }

    openedEntry?.let { entry ->
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        val detailScroll = rememberScrollState()
        AppDialog(
            onDismiss = { openedEntry = null },
            title = "${entry.levelLetter} · ${entry.tag}",
            hint = "${entry.timestamp} · ${entry.thread}",
            fillHeight = true,
            scrollable = false,
            maxHeightFraction = 0.94f,
            widthFraction = if (handheld) null else 0.8f,
            maxWidth = 1050.dp,
            footer = {
                TvActionButton(
                    text = stringResource(R.string.diagnostics_viewer_copy),
                    modifier = Modifier.testTag("diagnostics-copy"),
                    onClick = {
                        runCatching { clipboard.setText(AnnotatedString(entry.copyText())) }.onSuccess {
                            Toast.makeText(context, R.string.diagnostics_viewer_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TvActionButton(text = stringResource(R.string.back), modifier = Modifier.testTag("diagnostics-detail-back"), onClick = { openedEntry = null })
            },
        ) {
            // A long stack trace must be scrollable with DPAD as well as touch. At its boundaries
            // the direction key is free to move focus to the dialog's regular controls.
            Column(
                Modifier.fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val destination = when (event.key) {
                            Key.DirectionDown -> (detailScroll.value + 180).coerceAtMost(detailScroll.maxValue)
                            Key.DirectionUp -> (detailScroll.value - 180).coerceAtLeast(0)
                            else -> return@onPreviewKeyEvent false
                        }
                        if (destination == detailScroll.value) return@onPreviewKeyEvent false
                        scope.launch { detailScroll.animateScrollTo(destination) }
                        true
                    }
                    .focusable()
                    .verticalScroll(detailScroll),
            ) {
                Text(
                    text = entry.message,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
