package com.ambient.tv.ui.common

/**
 * An empty cached page is not a confirmed empty result while its first/current fetch
 * is outstanding. Populated snapshots remain visible during refresh.
 */
internal fun awaitingEmptyBrowseResult(
    isEmpty: Boolean,
    loading: Boolean,
    refreshing: Boolean,
): Boolean = isEmpty && (loading || refreshing)
