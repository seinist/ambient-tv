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
