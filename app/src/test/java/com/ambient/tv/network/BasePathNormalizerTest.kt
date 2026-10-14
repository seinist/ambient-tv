package com.ambient.tv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BasePathNormalizerTest {
    @Test
    fun blankBecomesEmpty() {
        assertEquals("", BasePathNormalizer.normalize(""))
        assertEquals("", BasePathNormalizer.normalize("  /  "))
    }

    @Test
    fun normalPathsGainLeadingSlash() {
        assertEquals("/jellyfin", BasePathNormalizer.normalize("jellyfin"))
        assertEquals("/jellyfin", BasePathNormalizer.normalize("/jellyfin/"))
        assertEquals("/a/b", BasePathNormalizer.normalize("a/b"))
    }

    @Test
    fun traversalSegmentsRejected() {
        for (bad in listOf("..", "../x", "a/../b", ".", "./x", "a/./b", "/../secret")) {
            assertThrows(IllegalArgumentException::class.java) { BasePathNormalizer.normalize(bad) }
        }
    }
}
