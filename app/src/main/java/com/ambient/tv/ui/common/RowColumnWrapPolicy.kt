package com.ambient.tv.ui.common

/**
 * Right at the last enabled tile wraps to the first enabled tile in physical row order.
 * The result is a LazyList index, so disabled cards retain their positions in [itemKeys].
 */
internal fun rowColumnWrapRightIndex(
    itemKeys: List<Any>,
    focusedItem: Any?,
    focusableItemKeys: List<Any> = itemKeys,
): Int? {
    if (focusedItem == null) return null
    val focusable = focusableItemKeys.toHashSet()
    val last = itemKeys.indexOfLast { it in focusable }
    if (last < 0 || itemKeys[last] != focusedItem) return null
    return itemKeys.indexOfFirst { it in focusable }.takeIf { it >= 0 }
}
