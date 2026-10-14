package com.ambient.tv.data.local

/** Controls what happens when a library or YouTube card is activated. */
object MediaSelectionPreferences {
    const val DETAILS = "details"
    const val PLAY = "play"
    const val ASK = "ask"

    val supported = setOf(DETAILS, PLAY, ASK)

    fun normalize(value: String?, default: String = DETAILS): String {
        val fallback = default.takeIf(supported::contains) ?: DETAILS
        return value?.takeIf(supported::contains) ?: fallback
    }
}
