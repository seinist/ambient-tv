package com.ambient.tv.ui.common

/** Persisted focus belongs to one concrete content view, identified by that view's state key. */
internal data class ContentFocusTarget(val itemId: String?, val rowId: String? = null)

/**
 * Restores the exact row + item when possible, then the same item in another row, and finally
 * the first item of the first non-empty row. The last case is also the initial-open policy.
 */
internal fun resolveContentFocusTarget(
    savedItemId: String?,
    savedRowId: String?,
    rows: List<Pair<String?, List<String>>>,
): ContentFocusTarget {
    val populated = rows.filter { it.second.isNotEmpty() }
    val exactRow = populated.firstOrNull { (rowId, items) ->
        rowId == savedRowId && savedItemId != null && savedItemId in items
    }
    val containingRow = exactRow ?: populated.firstOrNull { (_, items) ->
        savedItemId != null && savedItemId in items
    }
    val row = containingRow ?: populated.firstOrNull()
    val itemId = savedItemId?.takeIf { it in row?.second.orEmpty() } ?: row?.second?.firstOrNull()
    return ContentFocusTarget(itemId = itemId, rowId = row?.first)
}
