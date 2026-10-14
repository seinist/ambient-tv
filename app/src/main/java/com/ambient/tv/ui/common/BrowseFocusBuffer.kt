package com.ambient.tv.ui.common

internal data class BrowseFocusSnapshot(val itemKey: String, val rowId: String?)

/** Latest focus is immediately readable by background rebuilds; writes are coalesced per page. */
internal class BrowseFocusBuffer {
    private val latest = linkedMapOf<String, BrowseFocusSnapshot>()
    private val dirty = linkedSetOf<String>()

    @Synchronized fun update(page: String, itemKey: String, rowId: String?) {
        val next = BrowseFocusSnapshot(itemKey, rowId)
        if (latest[page] == next) return
        latest[page] = next
        dirty += page
    }

    @Synchronized fun get(page: String): BrowseFocusSnapshot? = latest[page]

    @Synchronized fun drain(): List<Pair<String, BrowseFocusSnapshot>> {
        val writes = dirty.mapNotNull { page -> latest[page]?.let { page to it } }
        dirty.clear()
        return writes
    }
}
