package com.ambient.tv.ui.common

import coil3.memory.MemoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkMemoryKeyIndexTest {
    private val card = MemoryCache.Key("thumb", mapOf("size" to "568x320"))
    private val detail = MemoryCache.Key("thumb", mapOf("size" to "840x472"))

    @Test
    fun cardKeySurvivesTheSameImageAtAnotherSize() {
        val index = ArtworkMemoryKeyIndex()
        index.remember("url", card)
        index.remember("url", detail)
        // Back on the list only the card-sized bitmap is still cached.
        assertEquals(card, index.cachedKey("url") { it == card })
        // The newest cached key wins when both are present.
        assertEquals(detail, index.cachedKey("url") { true })
        assertNull(index.cachedKey("url") { false })
        assertNull(index.cachedKey("other") { true })
    }

    @Test
    fun keepsOnlyTheNewestKeysPerModel() {
        val index = ArtworkMemoryKeyIndex(keysPerModel = 2)
        val third = MemoryCache.Key("thumb", mapOf("size" to "1920x1080"))
        index.remember("url", card)
        index.remember("url", detail)
        index.remember("url", third)
        assertNull(index.cachedKey("url") { it == card })
        assertEquals(detail, index.cachedKey("url") { it == detail })
    }
}
