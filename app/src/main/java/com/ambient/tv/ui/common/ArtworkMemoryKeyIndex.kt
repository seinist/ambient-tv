package com.ambient.tv.ui.common

import coil3.memory.MemoryCache

/**
 * Memory-cache keys of recent successful loads per artwork model, newest first.
 *
 * Coil only checks its memory cache once the image is measured; [ArtworkImage] uses these keys
 * to show a cached bitmap from the first frame. One key per model was not enough: the same URL
 * is often drawn at several sizes (a SmartTube card, the 420 dp image and the backdrop of its
 * detail page), each with its own key. The last one overwrote the card's key, so back on the
 * list the card found no bitmap and loaded again although it was still in memory.
 */
internal class ArtworkMemoryKeyIndex(
    private val maxModels: Int = 512,
    private val keysPerModel: Int = 3,
) {
    private val keys = object : java.util.LinkedHashMap<Any, List<MemoryCache.Key>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, List<MemoryCache.Key>>?) = size > maxModels
    }

    fun remember(model: Any, key: MemoryCache.Key) {
        val known = keys[model].orEmpty()
        if (known.firstOrNull() == key) return
        keys[model] = (listOf(key) + known.filter { it != key }).take(keysPerModel)
    }

    /** Newest key for [model] whose bitmap [isCached] still reports, or null. */
    fun cachedKey(model: Any, isCached: (MemoryCache.Key) -> Boolean): MemoryCache.Key? =
        keys[model]?.firstOrNull(isCached)
}
