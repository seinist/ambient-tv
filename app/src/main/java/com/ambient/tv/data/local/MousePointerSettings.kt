package com.ambient.tv.data.local

/** Shared by preferences, settings and the runtime cursor policy. Zero disables idle hiding. */
object MousePointerSettings {
    const val DEFAULT_SECONDS = 2
    const val PREFERENCE_KEY = "mouse_pointer_hide_seconds"
    val OPTIONS = listOf(0, 1, 2, 3, 5)

    fun normalize(seconds: Int): Int = seconds.takeIf { it in OPTIONS } ?: DEFAULT_SECONDS
}
