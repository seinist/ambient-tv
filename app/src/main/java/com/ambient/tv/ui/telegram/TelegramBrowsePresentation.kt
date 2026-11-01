package com.ambient.tv.ui.telegram

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData
import java.util.Locale

/** A view is a presentation choice: one source queue, same items and stable IDs. */
internal enum class MonogramBrowseLayout { ROWS, GRID, LIST, INFO_LIST }
internal enum class MonogramBrowseSort { DATE, TITLE, DURATION }

/** The loading placeholder that stands in for this view. */
internal fun MonogramBrowseLayout.placeholderLayout(): com.ambient.tv.ui.common.MonogramPlaceholderLayout = when (this) {
    MonogramBrowseLayout.ROWS -> com.ambient.tv.ui.common.MonogramPlaceholderLayout.ROWS
    MonogramBrowseLayout.GRID -> com.ambient.tv.ui.common.MonogramPlaceholderLayout.GRID
    MonogramBrowseLayout.LIST -> com.ambient.tv.ui.common.MonogramPlaceholderLayout.LIST
    MonogramBrowseLayout.INFO_LIST -> com.ambient.tv.ui.common.MonogramPlaceholderLayout.INFO_LIST
}

internal fun visibleMonogramRows(
    rows: List<ContentRowData>,
    query: String,
    channelId: String?,
    sort: MonogramBrowseSort,
    descending: Boolean,
): List<ContentRowData> {
    val needle = query.trim().lowercase(Locale.ROOT)
    val comparator: Comparator<MediaItem> = when (sort) {
        MonogramBrowseSort.DATE -> compareBy<MediaItem> { it.addedAt ?: 0L }
        MonogramBrowseSort.TITLE -> compareBy { it.title.lowercase(Locale.ROOT) }
        MonogramBrowseSort.DURATION -> compareBy { it.durationMs ?: 0L }
    }
    val order = if (descending) comparator.reversed() else comparator
    return rows.asSequence()
        .filter { channelId == null || it.id == channelId }
        .map { row ->
            row.copy(items = row.items.asSequence()
                .filter { needle.isBlank() || it.title.lowercase(Locale.ROOT).contains(needle) ||
                    it.parentTitle.orEmpty().lowercase(Locale.ROOT).contains(needle) ||
                    it.summary.orEmpty().lowercase(Locale.ROOT).contains(needle) }
                .sortedWith(order).toList())
        }
        .filter { it.items.isNotEmpty() }.toList()
}

internal fun flatMonogramItems(rows: List<ContentRowData>, sort: MonogramBrowseSort, descending: Boolean): List<MediaItem> {
    val entries = rows.flatMap(ContentRowData::items).distinctBy(MediaItem::ratingKey)
    val order: Comparator<MediaItem> = when (sort) {
        MonogramBrowseSort.DATE -> compareBy { it.addedAt ?: 0L }
        MonogramBrowseSort.TITLE -> compareBy { it.title.lowercase(Locale.ROOT) }
        MonogramBrowseSort.DURATION -> compareBy { it.durationMs ?: 0L }
    }
    return entries.sortedWith(if (descending) order.reversed() else order)
}
