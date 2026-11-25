package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkFadePolicyTest {
    @Test fun newUncachedArtworkUsesBriefFade() {
        assertEquals(200, artworkLoadFadeMillis(requested = true, reducedEffects = false, cachedInMemory = false))
    }

    @Test fun memoryCachedArtworkShowsImmediately() {
        assertEquals(0, artworkLoadFadeMillis(requested = true, reducedEffects = false, cachedInMemory = true))
    }

    @Test fun sparsamAlwaysSkipsFade() {
        assertEquals(0, artworkLoadFadeMillis(requested = true, reducedEffects = true, cachedInMemory = false))
        assertEquals(0, artworkLoadFadeMillis(requested = true, reducedEffects = true, cachedInMemory = true))
    }

    @Test fun explicitOptOutIsPreserved() {
        assertEquals(0, artworkLoadFadeMillis(requested = false, reducedEffects = false, cachedInMemory = false))
    }
}
