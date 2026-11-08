package com.ambient.tv.ui.player.backend

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvGpuShaderCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createsADedicatedDirectoryInsideTheAppCache() {
        val appCache = File(temporary.root, "cache")
        val directory = mpvGpuShaderCacheDirectory(appCache)
        assertEquals(File(appCache, "mpv-gpu-shaders"), directory)
        assertTrue(directory!!.isDirectory)
        assertTrue(directory.canWrite())
    }

    @Test fun repeatedPreparationPreservesCompiledPrograms() {
        val appCache = temporary.newFolder("cache")
        val directory = mpvGpuShaderCacheDirectory(appCache)!!
        val binary = File(directory, "cached-program").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        val markerTime = 1_600_000_000_000L
        assertTrue(binary.setLastModified(markerTime))

        assertEquals(directory, mpvGpuShaderCacheDirectory(appCache))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), binary.readBytes())
        assertEquals(markerTime, binary.lastModified())
    }

    @Test fun aConflictingFileDisablesCachingWithoutDeletingIt() {
        val appCache = temporary.newFolder("cache")
        val conflicting = File(appCache, "mpv-gpu-shaders").apply { writeText("keep") }

        assertNull(mpvGpuShaderCacheDirectory(appCache))
        assertEquals("keep", conflicting.readText())
    }

    @Test fun anUnavailableParentDisablesCachingWithoutBreakingPlaybackSetup() {
        val appCache = temporary.newFile("not-a-directory")
        assertNull(mpvGpuShaderCacheDirectory(appCache))
        assertTrue(appCache.isFile)
    }
}
