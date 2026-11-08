package com.ambient.tv.ui.telegram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.common.ContentRowData

/** Save the latest selection without making the full feed observe every D-pad hop. */
internal class MonogramFocusMemory(var key: String? = null)

internal val MonogramFocusMemorySaver = Saver<MonogramFocusMemory, String>(
    save = { it.key.orEmpty() },
    restore = { MonogramFocusMemory(it.takeIf(String::isNotEmpty)) },
)

@Composable
internal fun rememberMonogramFocusMemory(sectionKey: String, returnedKey: String?, returnRevision: Long = 0L): MonogramFocusMemory {
    val memory = rememberSaveable(sectionKey, saver = MonogramFocusMemorySaver) { MonogramFocusMemory() }
    // A queue return overrides the old page selection once, before the initial focus pass.
    remember(memory, returnedKey, returnRevision) { if (returnedKey != null) memory.key = returnedKey; Unit }
    return memory
}

/** Built only when the displayed rows/order changes, never while moving between their cards. */
internal class MonogramBrowseFocusIndex(rows: List<ContentRowData>, flatItems: List<MediaItem>) {
    val rowKeys: List<Pair<String, List<String>>> = rows.map { row -> row.id to row.items.map { it.ratingKey } }
    val flatKeys: List<String> = flatItems.map { it.ratingKey }
    private val flatKeySet = flatKeys.toHashSet()
    private val rowByItem = buildMap {
        rowKeys.forEach { (row, keys) -> keys.forEach { key -> putIfAbsent(key, row) } }
    }
    private val positionsByRow = buildMap {
        rowKeys.forEach { (row, keys) ->
            putIfAbsent(row, buildMap { keys.forEachIndexed { index, key -> putIfAbsent(key, index) } })
        }
    }

    fun contains(key: String): Boolean = key in flatKeySet
    fun rowForItem(key: String?): String? = rowByItem[key]
    fun indexInRow(row: String, key: String): Int = positionsByRow[row]?.get(key) ?: -1
}
