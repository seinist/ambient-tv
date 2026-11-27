package com.ambient.tv.data.telegram

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewFileIndexTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun bootstrapAndAccessOrderEvictOldestNotRecentlyUsed() {
        val dir = folder.root
        val a = File(dir, "a.img").apply { writeBytes(ByteArray(3)); setLastModified(1_000) }
        val b = File(dir, "b.img").apply { writeBytes(ByteArray(3)); setLastModified(2_000) }
        val cache = PreviewFileIndex(dir, 2, 6, clock = { 3_000 }) { it.extension == "img" }
        assertTrue(cache.hit(a))
        val c = File(dir, "c.img").apply { writeBytes(ByteArray(3)) }
        cache.publish(c)
        assertTrue(a.exists())
        assertFalse(b.exists())
        assertTrue(c.exists())
    }

    @Test fun missingFileAndInvalidationDoNotKeepGhostBytes() {
        val dir = folder.root
        val cache = PreviewFileIndex(dir, 2, 6) { it.extension == "img" }
        val a = File(dir, "a.img").apply { writeBytes(ByteArray(3)) }
        cache.publish(a)
        a.delete()
        assertFalse(cache.hit(a))
        val b = File(dir, "b.img").apply { writeBytes(ByteArray(3)) }
        cache.publish(b)
        cache.removeMatching { it == b }
        b.delete()
        val c = File(dir, "c.img").apply { writeBytes(ByteArray(6)) }
        cache.publish(c)
        assertTrue(cache.hit(c))
    }
}
