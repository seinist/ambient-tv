package com.ambient.tv.ui.common

internal data class BrowseFocusKey(val row: Any?, val item: Any, val content: Boolean)

/** The two immediate neighbours before a refresh, not the next surviving item after it. */
internal data class BrowseRefreshFocusAnchor(
    val key: BrowseFocusKey,
    val next: BrowseFocusKey?,
    val previous: BrowseFocusKey?,
)

/**
 * Immutable display order for one feed. Rows are part of a tile's identity: the same video in
 * another recommendation row must not pull focus across the page after a refresh.
 */
internal class BrowseRefreshFocusSnapshot(rows: List<Pair<Any?, List<Any>>>) {
    private val firstByRow = LinkedHashMap<Any?, BrowseFocusKey>()
    private val anchors = LinkedHashMap<BrowseFocusKey, BrowseRefreshFocusAnchor>()

    init {
        val rowKeys = LinkedHashMap<Any?, LinkedHashSet<BrowseFocusKey>>()
        rows.forEach { (row, items) ->
            val keys = rowKeys.getOrPut(row) { LinkedHashSet() }
            items.forEach { keys += BrowseFocusKey(row, it, true) }
        }
        rowKeys.forEach { (row, values) ->
            val keys = values.toList()
            keys.firstOrNull()?.let { firstByRow[row] = it }
            keys.forEachIndexed { index, key ->
                anchors[key] = BrowseRefreshFocusAnchor(key, keys.getOrNull(index + 1), keys.getOrNull(index - 1))
            }
        }
    }

    fun anchor(key: BrowseFocusKey): BrowseRefreshFocusAnchor? = anchors[key]

    /** Keep the tile, then its old next/previous neighbour, otherwise row index zero. */
    fun resolve(anchor: BrowseRefreshFocusAnchor): BrowseFocusKey? = when {
        anchor.key in anchors -> anchor.key
        anchor.next != null && anchor.next in anchors -> anchor.next
        anchor.previous != null && anchor.previous in anchors -> anchor.previous
        else -> firstByRow[anchor.key.row] ?: firstByRow.values.firstOrNull()
    }
}

/**
 * Initial entry and playback return keep their existing exact-item semantics. Subsequent
 * snapshots use the refresh policy, including when a toolbar owns focus and the missing tile
 * is only the remembered content address. Recreate this memory for a new view/return identity.
 */
internal class ContentRefreshFocusMemory {
    private var previous: BrowseRefreshFocusSnapshot? = null
    private var rememberedAnchor: BrowseRefreshFocusAnchor? = null

    fun resolve(
        savedItemId: String?,
        savedRowId: String?,
        rows: List<Pair<String?, List<String>>>,
    ): ContentFocusTarget {
        val current = BrowseRefreshFocusSnapshot(rows)
        val key = savedItemId?.let { BrowseFocusKey(savedRowId, it, true) }
        val before = key?.let { previous?.anchor(it) }
            ?: rememberedAnchor?.takeIf { it.key == key }
        val target = if (previous != null && before != null) {
            current.resolve(before).let { ContentFocusTarget(it?.item as? String, it?.row as? String) }
        } else {
            resolveContentFocusTarget(savedItemId, savedRowId, rows)
        }
        previous = current
        rememberedAnchor = key?.let(current::anchor) ?: before
        return target
    }
}
