package com.ambient.tv.games

import com.ambient.tv.data.files.SeekableMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure JVM tests: no emulator, NAS, CoreManager or Android application initialization. */
class GameArchiveOptimizationsTest {
    private class Bytes(private val bytes: ByteArray) : SeekableMedia {
        override val size: Long get() = bytes.size.toLong()
        var bytesRead = 0L
        override fun read(position: Long, buffer: ByteArray, count: Int): Int {
            if (position < 0 || position >= bytes.size) return -1
            val n = minOf(count, bytes.size - position.toInt())
            bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + n)
            bytesRead += n
            return n
        }
        override fun close() = Unit
    }

    private fun gba(name: String, size: Int = 1024): ByteArray =
        ByteArray(size).also {
            name.toByteArray(StandardCharsets.US_ASCII).copyInto(it, 0xa0)
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { archive ->
                entries.forEach { (name, bytes) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }.toByteArray()

    @Test fun zipCatalogMetadataGetsInternalTitleAndCrcWithoutReadingEntireRom() {
        val rom = ByteArray(2 * 1024 * 1024)
        Random(42).nextBytes(rom)
        "ADVANCE WARS".toByteArray(StandardCharsets.US_ASCII).copyInto(rom, 0xa0)
        val source = Bytes(zip("opaque-file.gba" to rom))
        val metadata = GameArchives.probe("hidden.zip", source)
        assertNotNull(metadata)
        assertEquals("gba", metadata!!.system?.id)
        assertEquals("ADVANCE WARS", metadata.internalTitle)
        val checksum = CRC32().apply { update(rom) }.value
            .toString(16).uppercase().padStart(8, '0')
        assertEquals(checksum, metadata.crc32)
        assertTrue("ZIP index scan must not download the 2MiB ROM",
            source.bytesRead < 1024L * 1024)
    }

    @Test fun directZipStageWritesOnlyOneRomAndVerifiesItsBytes() {
        val rom = gba("GOLDEN SUN", 500_000)
        val bytes = zip("game.gba" to rom, "README.txt" to "safe".toByteArray())
        val folder = Files.createTempDirectory("ambient-game-stage").toFile()
        try {
            val source = Bytes(bytes)
            val result = GameArchives.stageSingle("game.zip", source, folder,
                1024L * 1024) { }
            assertNotNull(result)
            assertArrayEquals(rom, result!!.readBytes())
            assertTrue(result.name == "game.gba")
            assertTrue("Staging must not retain the compressed archive",
                folder.walkTopDown().none { it.name.endsWith(".zip") })
        } finally { folder.deleteRecursively() }
    }

    @Test fun multiRomZipDoesNotInventACoreChoiceOrStagePartialContent() {
        val folder = Files.createTempDirectory("ambient-games-multi").toFile()
        try {
            val source = Bytes(zip("one.gba" to gba("ONE ROM"),
                "two.gba" to gba("TWO ROM")))
            assertNull(GameArchives.stageSingle("many.zip", source, folder,
                1024L * 1024) { })
            assertTrue(folder.walkTopDown().none(File::isFile))
        } finally { folder.deleteRecursively() }
    }

    @Test fun gzipTitleAndDirectStreamRequireNoArchiveCopy() {
        val rom = gba("METROIDFUS", 90_000)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(rom) }
        val metadata = GameArchives.probe("game.gba.gz", Bytes(output.toByteArray()))
        assertEquals("gba", metadata?.system?.id)
        assertEquals("METROIDFUS", metadata?.internalTitle)
        val folder = Files.createTempDirectory("ambient-game-gz").toFile()
        try {
            val staged = GameArchives.stageSingle("game.gba.gz",
                Bytes(output.toByteArray()), folder, 1024L * 1024) { }
            assertNotNull(staged)
            assertArrayEquals(rom, staged!!.readBytes())
        } finally { folder.deleteRecursively() }
    }

    @Test fun archiveTraversalAndDecompressionBudgetAreRejected() {
        val folder = Files.createTempDirectory("ambient-game-unsafe").toFile()
        try {
            val escaped = Bytes(zip("../escape.gba" to gba("ESCAPE")))
            assertThrows(GameArchiveException::class.java) {
                GameArchives.stageSingle("unsafe.zip", escaped, folder, 1024L * 1024) { }
            }
            val tooBig = Bytes(zip("game.gba" to gba("GAME ROM", 150_000)))
            assertThrows(GameArchiveException::class.java) {
                GameArchives.stageSingle("game.zip", tooBig, folder, 16L * 1024) { }
            }
            assertTrue(folder.walkTopDown().none(File::isFile))
        } finally { folder.deleteRecursively() }
    }

    @Test fun rarMetadataUsesOnlySafeOuterFileNameHint() {
        assertEquals("nes", GameArchives.probe("collection.nes.rar", Bytes(byteArrayOf()))?.system?.id)
        assertNull(GameArchives.probe("opaque.rar", Bytes(byteArrayOf()))?.system)
    }
}
