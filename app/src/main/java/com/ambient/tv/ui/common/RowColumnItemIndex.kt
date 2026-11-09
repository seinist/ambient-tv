package com.ambient.tv.ui.common

/** Physical row indices and enabled endpoints, rebuilt only when a published snapshot changes. */
internal class RowColumnItemIndex {
    private var indexedItems: List<Any>? = null
    private var indexedFocusableItems: List<Any>? = null
    private var enabledIndices: Map<Any, Int> = emptyMap()
    private var firstEnabledIndex: Int? = null
    private var firstEnabledKey: Any? = null
    private var lastEnabledIndex: Int? = null
    private var lastEnabledKey: Any? = null

    private fun update(itemKeys: List<Any>, focusableItemKeys: List<Any>) {
        if (itemKeys === indexedItems && focusableItemKeys === indexedFocusableItems) return
        val enabled = if (focusableItemKeys === itemKeys) null else focusableItemKeys.toHashSet()
        val indices = HashMap<Any, Int>(itemKeys.size)
        var first: Int? = null
        var firstKey: Any? = null
        var lastIndex: Int? = null
        var last: Any? = null
        itemKeys.forEachIndexed { index, key ->
            if (enabled == null || key in enabled) {
                // Duplicate identities keep indexOf's first physical match for row entry.
                indices.putIfAbsent(key, index)
                if (first == null) { first = index; firstKey = key }
                lastIndex = index
                last = key
            }
        }
        enabledIndices = indices
        firstEnabledIndex = first
        firstEnabledKey = firstKey
        lastEnabledIndex = lastIndex
        lastEnabledKey = last
        indexedItems = itemKeys
        indexedFocusableItems = focusableItemKeys
    }

    fun entryIndex(
        itemKeys: List<Any>,
        rememberedItem: Any?,
        defaultItem: Any?,
        focusableItemKeys: List<Any> = itemKeys,
    ): Int? {
        update(itemKeys, focusableItemKeys)
        return rememberedItem?.let(enabledIndices::get)
            ?: defaultItem?.let(enabledIndices::get)
            ?: firstEnabledIndex
    }

    fun wrapLeftIndex(
        itemKeys: List<Any>,
        focusedItem: Any?,
        focusableItemKeys: List<Any> = itemKeys,
    ): Int? {
        if (focusedItem == null) return null
        update(itemKeys, focusableItemKeys)
        return lastEnabledIndex?.takeIf { focusedItem == firstEnabledKey }
    }

    fun wrapRightIndex(
        itemKeys: List<Any>,
        focusedItem: Any?,
        focusableItemKeys: List<Any> = itemKeys,
    ): Int? {
        if (focusedItem == null) return null
        update(itemKeys, focusableItemKeys)
        return firstEnabledIndex?.takeIf { focusedItem == lastEnabledKey }
    }
}
