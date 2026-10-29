package com.ambient.tv.ui.common

/** A row keeps the user's position before considering its current first-entry suggestion. */
internal fun rowColumnEntryIndex(
    itemKeys: List<Any>,
    rememberedItem: Any?,
    defaultItem: Any?,
    focusableItemKeys: List<Any> = itemKeys,
): Int? {
    val focusable = focusableItemKeys.toHashSet()
    val remembered = rememberedItem?.takeIf { it in focusable }?.let(itemKeys::indexOf)?.takeIf { it >= 0 }
    val suggested = defaultItem?.takeIf { it in focusable }?.let(itemKeys::indexOf)?.takeIf { it >= 0 }
    return remembered ?: suggested ?: itemKeys.indexOfFirst { it in focusable }.takeIf { it >= 0 }
}
