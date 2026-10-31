package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info

/**
 * An empty cached page is not a confirmed empty result while its first/current fetch
 * is outstanding. Populated snapshots remain visible during refresh.
 */
internal fun awaitingEmptyBrowseResult(
    isEmpty: Boolean,
    loading: Boolean,
    refreshing: Boolean,
): Boolean = isEmpty && (loading || refreshing)

/** All browse empty/error screens use one centered, focus-safe presentation.
 * The caller supplies the remaining *content* viewport (beneath headers, filters and tabs).
 * This keeps the message centered in that viewport, including list/grid modes. */
enum class BrowseStatusKind { EMPTY, ERROR, INFO }

@androidx.compose.runtime.Composable
fun BrowseStatusPanel(
    title: String,
    body: String? = null,
    kind: BrowseStatusKind = BrowseStatusKind.EMPTY,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier.fillMaxSize(),
) {
    val icon = when (kind) {
        BrowseStatusKind.EMPTY -> androidx.compose.material.icons.Icons.Default.Inbox
        BrowseStatusKind.ERROR -> androidx.compose.material.icons.Icons.Default.ErrorOutline
        BrowseStatusKind.INFO -> androidx.compose.material.icons.Icons.Default.Info
    }
    StatePanel(
        title = title,
        body = body,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
        icon = icon,
        centered = true,
    )
}
