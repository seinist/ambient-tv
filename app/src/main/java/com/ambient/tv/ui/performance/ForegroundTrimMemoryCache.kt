package com.ambient.tv.ui.performance

import android.content.ComponentCallbacks2
import coil3.memory.MemoryCache

/**
 * Coil memory cache that does not give up the browse artwork on a mild foreground memory warning.
 *
 * Coil halves its cache on every [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW]. MediaTek TVs
 * (Philips TA1) run many system services and send that level again and again while the app is
 * in front, so a few halvings emptied the cache and every card scrolled off screen loaded again
 * on the way back. The SHIELD rarely sends it, which is why it kept its cards.
 *
 * - RUNNING_LOW: trims at most to [RUNNING_LOW_KEEP_FRACTION] of the configured size.
 * - RUNNING_CRITICAL and everything Coil does in the background (clear, max size) pass through.
 *
 * [trimLevel] is the last level the application received; it is read when Coil calls
 * [trimToSize] from its own callback for the same event.
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class) // initialMaxSize is delegated unchanged.
internal class ForegroundTrimMemoryCache(
    private val delegate: MemoryCache,
    private val trimLevel: () -> Int,
) : MemoryCache by delegate {
    override fun trimToSize(size: Long) {
        delegate.trimToSize(foregroundTrimTarget(size, trimLevel(), delegate.maxSize))
    }
}

internal const val RUNNING_LOW_KEEP_FRACTION = 0.75

/** Size Coil's [MemoryCache.trimToSize] request is reduced to for [level]. */
internal fun foregroundTrimTarget(requested: Long, level: Int, maxSize: Long): Long =
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) requested
    else maxOf(requested, (maxSize * RUNNING_LOW_KEEP_FRACTION).toLong())
