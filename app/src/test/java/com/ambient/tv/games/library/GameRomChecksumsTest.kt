package com.ambient.tv.games.library

import java.io.IOException
import java.util.Random
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class GameRomChecksumsTest {
    private fun crc(bytes: ByteArray) = GameRomChecksums.hex(CRC32().apply { update(bytes) }.value)

    @Test fun headerlessChecksumMatchesActualPayloadForDifferentRomSizesAndHeaderContents() {
        for (banks in listOf(1, 2, 7, 32, 96)) {
            val random = Random(banks.toLong())
            val header = ByteArray(512).also(random::nextBytes)
            val payload = ByteArray(banks * 32 * 1024).also(random::nextBytes)
            val full = header + payload
            val result = GameRomChecksums.forRom("snes", full.size.toLong(), header, crc(full))
            assertEquals("Raw provider identity must not change", crc(full), result.crc32)
            assertEquals(crc(payload), result.datCrc32)
        }
    }

    @Test fun rawReadStreamsExactlyOnceAndHandlesShortReadsAcrossTheCopierHeader() {
        val payload = ByteArray(2 * 1024 * 1024).also(Random(42)::nextBytes)
        val full = ByteArray(512).also(Random(7)::nextBytes) + payload
        var read = 0L
        val result = GameRomChecksums.read("snes", full.size.toLong()) { offset, target, count ->
            assertEquals(read, offset)
            assertTrue(count <= 128 * 1024)
            val length = minOf(count, 107)
            full.copyInto(target, 0, offset.toInt(), offset.toInt() + length)
            read += length
            length
        }
        assertEquals(full.size.toLong(), read)
        assertEquals(crc(full), result.crc32)
        assertEquals(crc(payload), result.datCrc32)
    }

    @Test fun rawReadRejectsTruncatedContentInsteadOfCachingANegativeMatch() {
        assertThrows(IOException::class.java) {
            GameRomChecksums.read("snes", 32768) { _, _, _ -> -1 }
        }
    }

    @Test fun unheaderedAndOtherSystemsKeepOnlyTheirActualChecksum() {
        val prefix = ByteArray(512)
        assertNull(GameRomChecksums.forRom("snes", 32768, prefix, "ABCDEF12").datCrc32)
        assertNull(GameRomChecksums.forRom("gba", 32768 + 512L, prefix, "ABCDEF12").datCrc32)
        assertNull(GameRomChecksums.forRom("snes", 32768 + 512L, prefix.copyOf(511), "ABCDEF12").datCrc32)
        assertNull(GameRomChecksums.forRom("snes", 32768 + 512L, prefix, "not a crc").crc32)
        assertNull(GameRomChecksums.forRom("snes", 512, prefix, "ABCDEF12").datCrc32)
    }

    @Test fun catalogSearchIgnoresPunctuationSpacingAndAccents() {
        assertEquals("supermarioworld", gameDatSearchKey("SUPER MARIOWORLD"))
        assertEquals("supermarioworldusa", gameDatSearchKey("Super Mario World (USA)"))
        assertEquals("pokemonblue", gameDatSearchKey("Pokémon_Blue"))
        assertEquals("", gameDatSearchKey(" %_[()] "))
    }

    @Test fun trimmedDsChecksumMatchesFullyPaddedCartridge() {
        val rom = ByteArray(100_000).also(Random(42)::nextBytes)
        "ABCD".toByteArray().copyInto(rom, 0x0c)
        rom[0x12] = 0
        rom[0x14] = 0
        java.nio.ByteBuffer.wrap(rom).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x80, rom.size)
        val padded = rom.copyOf(128 * 1024).apply { fill(0xff.toByte(), rom.size) }
        val result = GameRomChecksums.forRom("nds", rom.size.toLong(), rom.copyOf(512), crc(rom))
        assertEquals(crc(rom), result.crc32)
        assertEquals(crc(padded), result.datCrc32)
        assertTrue(GameRomChecksums.canHash("game.nds", 512L * 1024 * 1024))
        assertFalse(GameRomChecksums.canHash("game.nds", 512L * 1024 * 1024 + 1))
        assertTrue(GameRomChecksums.canHash("game.3ds", 4L * 1024 * 1024 * 1024))
        assertFalse(GameRomChecksums.canHash("game.3ds", 4L * 1024 * 1024 * 1024 + 1))
    }

    @Test fun explicitChecksumScansStayBoundedAndExcludeArchivesAndDiscImages() {
        assertTrue(GameRomChecksums.canHash("game.smc", 2L * 1024 * 1024))
        assertFalse(GameRomChecksums.canHash("game.smc", 128L * 1024 * 1024 + 1))
        assertFalse(GameRomChecksums.canHash("game.smc", 0))
        for (extension in listOf("zip", "7z", "rar", "gz", "iso", "cue", "chd", "m3u", "pbp"))
            assertFalse(GameRomChecksums.canHash("game.$extension", 1024))
    }
}
