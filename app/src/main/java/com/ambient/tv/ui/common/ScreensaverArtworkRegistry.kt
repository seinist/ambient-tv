package com.ambient.tv.ui.common

import androidx.compose.runtime.mutableStateListOf

/**
 * Small in-memory pool of artwork the user has actually browsed in this app session.
 * The screensaver never needs to wake a backend just to decorate an idle screen.
 */
object ScreensaverArtworkRegistry {
    private const val MAX_ITEMS = 24
    val urls = mutableStateListOf<String>()

    fun offer(vararg candidates: String?) {
        candidates.filterNotNull().map(String::trim).filter(String::isNotEmpty).forEach { url ->
            urls.remove(url)
            urls.add(0, url)
        }
        while (urls.size > MAX_ITEMS) urls.removeAt(urls.lastIndex)
    }

    fun offerAll(candidates: Iterable<String?>) {
        candidates.forEach { offer(it) }
    }
}
