package com.ambient.tv.ui.common

/** A row keeps the user's position before considering its current first-entry suggestion. */
internal fun rowColumnEntryIndex(
    itemKeys: List<Any>,
    rememberedItem: Any?,
    defaultItem: Any?,
    focusableItemKeys: List<Any> = itemKeys,
): Int? = RowColumnItemIndex().entryIndex(itemKeys, rememberedItem, defaultItem, focusableItemKeys)
