package com.ambient.tv.ui.settings

/** Positions come from the actual visible list, so sections and conditional rows cannot shift a search target. */
internal class SettingsItemIndex {
    private var positions: Map<String, Int> = emptyMap()

    fun replace(items: Map<String, Int>) {
        positions = items.toMap()
    }

    fun indexOf(key: String?): Int? = key?.let(positions::get)

    /** Explicit search/link landings start at the top if no specific row is available. Normal Back restores scroll. */
    fun landingPosition(key: String?, requested: Boolean): Int? = if (requested) indexOf(key) ?: 0 else null
}
