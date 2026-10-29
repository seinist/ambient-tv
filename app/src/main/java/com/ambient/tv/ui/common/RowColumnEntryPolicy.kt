package com.ambient.tv.ui.common

/** A row keeps the user's position before considering its current first-entry suggestion. */
internal fun rowColumnEntryIndex(
    itemKeys: List<Any>,
    rememberedItem: Any?,
    defaultItem: Any?,
): Int? {
    if (itemKeys.isEmpty()) return null
    val remembered = rememberedItem?.let(itemKeys::indexOf)?.takeIf { it >= 0 }
    val suggested = defaultItem?.let(itemKeys::indexOf)?.takeIf { it >= 0 }
    return remembered ?: suggested ?: 0
}
