package com.ambient.tv.ui.common

/**
 * Loading a new card bitmap should be gentle, without animating cache-backed navigation.
 * Coil also suppresses its transition on genuine memory-cache hits that aren't in our index.
 * This policy only changes presentation; decode, prefetch, and cache keys stay untouched.
 */
internal const val ARTWORK_LOAD_FADE_MS = 200

internal fun artworkLoadFadeMillis(
    requested: Boolean,
    reducedEffects: Boolean,
    cachedInMemory: Boolean,
): Int = if (requested && !reducedEffects && !cachedInMemory) ARTWORK_LOAD_FADE_MS else 0

/** Do not retain the inline image after a sharp image has completed its transition. */
internal fun showProgressiveArtworkPreview(
    admitted: Boolean, loading: Boolean, fading: Boolean, failed: Boolean,
): Boolean = !admitted || loading || fading || failed
