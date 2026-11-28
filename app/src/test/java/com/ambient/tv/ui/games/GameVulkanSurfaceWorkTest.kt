package com.ambient.tv.ui.games

import org.junit.Assert.*
import org.junit.Test

class GameVulkanSurfaceWorkTest {
    @Test fun metadataChangesDoNotRetireAnUnchangedWindow() {
        val work = GameVulkanSurfaceWork<String>()
        assertTrue(work.needsAttachment(1))
        work.attached(1)
        work.rememberFormats(1, "sdr", 3)
        assertEquals(3, work.cachedFormats(1, "sdr"))
        assertNull(work.cachedFormats(1, "hdr"))
        assertFalse(work.needsAttachment(1))
        work.rememberFormats(1, "hdr", 7)
        assertEquals(7, work.cachedFormats(1, "hdr"))
        assertFalse(work.needsAttachment(1))
    }

    @Test fun repeatedSurfaceObjectWithNewRevisionAlwaysReattaches() {
        val work = GameVulkanSurfaceWork<String>()
        work.attached(10)
        work.rememberFormats(10, "sdr", 3)
        assertTrue(work.needsAttachment(11))
        assertNull(work.cachedFormats(11, "sdr"))
        work.attached(11)
        assertFalse(work.needsAttachment(11))
        work.detached()
        assertTrue(work.needsAttachment(11))
    }

    @Test fun failedProbeIsNotCachedAndDetachDropsBothOwners() {
        val work = GameVulkanSurfaceWork<String>()
        work.attached(1)
        work.rememberFormats(1, "sdr", 3)
        work.rememberFormats(1, "sdr", 0)
        assertNull(work.cachedFormats(1, "sdr"))
        work.rememberFormats(1, "sdr", 3)
        work.detached()
        assertNull(work.cachedFormats(1, "sdr"))
        assertTrue(work.needsAttachment(1))
    }
}
