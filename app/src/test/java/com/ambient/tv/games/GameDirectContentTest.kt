package com.ambient.tv.games

import com.ambient.tv.data.files.SeekableMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GameDirectContentTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeMedia(override val size: Long, private val shortReads: Boolean = false) : SeekableMedia {
        var calls = 0
        override fun read(position: Long, buffer: ByteArray, count: Int): Int {
            calls++
            if (position >= size) return -1
            val n = minOf(count.toLong(), size - position, if (shortReads) 777L else Long.MAX_VALUE).toInt()
            for (i in 0 until n) buffer[i] = ((position + i) and 255).toByte()
            return n
        }
        override fun close() {}
    }

    @Test fun readWindowsStayBoundedAndHandleLargeOffsets() {
        val media = FakeMedia(7L * 1024 * 1024 * 1024 + 123, shortReads = true)
        val windows = GameReadWindows(media, blockBytes = 4096, maxBlocks = 2)
        fun expected(start: Long, n: Int): ByteArray = ByteArray(n) { ((start + it) and 255).toByte() }
        val start = 5L * 1024 * 1024 * 1024 + 4093
        val result = ByteArray(14_000)
        assertEquals(result.size, windows.read(start, result, result.size))
        assertArrayEquals(expected(start, result.size), result)
        val calls = media.calls
        assertEquals(50, windows.read(start + 13_950, ByteArray(50), 50))
        assertEquals("recent read should hit the window cache", calls, media.calls)
        val tail = ByteArray(1024)
        assertEquals(123, windows.read(media.size - 123, tail, tail.size))
        assertArrayEquals(expected(media.size - 123, 123), tail.copyOf(123))
        assertEquals(0, windows.read(media.size, ByteArray(12), 12))
    }

    @Test fun sequentialMissesRecycleLruBuffersAndShortReadScratch() {
        for (shortReads in listOf(false, true)) {
            val buffers = java.util.IdentityHashMap<ByteArray, Boolean>()
            var calls = 0
            val media = object : SeekableMedia {
                override val size = 12L * 4096 + 17
                override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                    calls++
                    buffers[buffer] = true
                    val n = minOf(count, if (shortReads) 777 else count)
                    for (i in 0 until n) buffer[i] = ((position + i) / 257).toByte()
                    return n
                }
                override fun close() {}
            }
            val windows = GameReadWindows(media, blockBytes = 4096, maxBlocks = 2)
            fun checkRead(offset: Long, count: Int) {
                val bytes = ByteArray(count)
                assertEquals(count, windows.read(offset, bytes, count))
                assertArrayEquals(ByteArray(count) { ((offset + it) / 257).toByte() }, bytes)
            }
            checkRead(0, 4096)
            checkRead(4096, 4096)
            checkRead(0, 1) // Refresh the first block; the second becomes least recently used.
            checkRead(8192, 4096)
            val afterThird = calls
            checkRead(0, 1)
            assertEquals(afterThird, calls)
            checkRead(4096, 1)
            assertTrue(calls > afterThird)
            for (i in 3..11) checkRead(i * 4096L, 4096)
            checkRead(media.size - 17, 17)
            assertEquals(if (shortReads) 3 else 2, buffers.size)
        }
    }

    @Test fun recycledWindowNeverPublishesStaleBytesOnFailureOrPrematureEof() {
        var fail = true
        val media = object : SeekableMedia {
            override val size = 3L * 4096
            override fun read(position: Long, buffer: ByteArray, count: Int): Int {
                if (position == 4096L && fail) {
                    buffer[0] = 99
                    throw java.io.IOException("read failed")
                }
                if (position >= 8192 + 5) return -1
                val n = if (position == 8192L) 5 else count
                for (i in 0 until n) buffer[i] = (position / 4096 + 1).toByte()
                return n
            }
            override fun close() {}
        }
        val windows = GameReadWindows(media, blockBytes = 4096, maxBlocks = 1)
        val byte = ByteArray(1)
        assertEquals(1, windows.read(0, byte, 1))
        assertEquals(1, byte[0].toInt())
        assertTrue(runCatching { windows.read(4096, byte, 1) }.exceptionOrNull() is java.io.IOException)
        assertEquals(1, windows.read(0, byte, 1))
        assertEquals(1, byte[0].toInt())
        fail = false
        assertEquals(1, windows.read(4096, byte, 1))
        assertEquals(2, byte[0].toInt())
        val tail = ByteArray(5)
        assertEquals(5, windows.read(8192, tail, 5))
        assertArrayEquals(ByteArray(5) { 3 }, tail)
        assertTrue(runCatching { windows.read(8197, byte, 1) }.exceptionOrNull() is java.io.IOException)
        assertEquals(1, windows.read(4096, byte, 1))
        assertEquals(2, byte[0].toInt())
    }

    @Test fun originalIsoHeadersAndPathsIdentifyThePlatform() {
        fun iso(wii: Boolean): ByteArray = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).run {
            putInt(if (wii) 0x18 else 0x1c, if (wii) 0x5D1C9EA3 else 0xC2339F3DL.toInt())
            array()
        }
        assertEquals("wii", GameSystems.forSourceFile("disc.iso", "/NAS/PSP/disc.iso", iso(true))?.id)
        assertEquals("gamecube", GameSystems.forSourceFile("disc.iso", "/NAS/Wii/disc.iso", iso(false))?.id)
        assertEquals("psp", GameSystems.forSourceFile("disc.iso", "/NAS/PSP/disc.iso")?.id)
        assertEquals(null, GameSystems.forSourceFile("disc.iso", "/NAS/unknown/disc.iso"))
    }

    @Test fun directAccessRequiresKnownFullpathCoreAndSingleDiscFile() {
        assertTrue(GameSystems.byId("psp")!!.core.directFile)
        assertTrue(GameSystems.byId("gamecube")!!.core.directFile)
        assertTrue(GameSystems.byId("wiiu")!!.core.directFile)
        assertTrue(GameSystems.byId("ps2")!!.core.directFile)
        assertFalse(GameSystems.byId("nes")!!.core.directFile)
        assertFalse("cue" in GameRomPreparer.DIRECT_EXTENSIONS)
        assertFalse("zip" in GameRomPreparer.DIRECT_EXTENSIONS)
        assertFalse("gdi" in GameRomPreparer.DIRECT_EXTENSIONS)
        assertTrue("chd" in GameRomPreparer.DIRECT_EXTENSIONS)
        assertTrue("wua" in GameRomPreparer.DIRECT_EXTENSIONS)
    }
}
