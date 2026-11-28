package com.ambient.tv.games

import com.ambient.tv.data.files.SeekableMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipMethod

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

    @Test fun headeredSnesArchivesKeepRawCrcAndMatchHeaderlessDatWithoutFullDecompression() {
        val payload = ByteArray(2 * 1024 * 1024).also(Random(42)::nextBytes)
        val rom = ByteArray(512).also(Random(7)::nextBytes) + payload
        "SUPER MARIOWORLD     ".toByteArray().copyInto(rom, 512 + 0x7fc0)
        rom.copyInto(payload, 0, 512)
        fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
            .toString(16).uppercase().padStart(8, '0')
        val zip = Bytes(zip("opaque.smc" to rom))
        val gzip = Bytes(ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(rom) }
        }.toByteArray())
        for ((name, source) in listOf("opaque.zip" to zip, "opaque.smc.gz" to gzip)) {
            val metadata = GameArchives.probe(name, source)!!
            assertEquals(crc(rom), metadata.crc32)
            assertEquals(crc(payload), metadata.datCrc32)
            assertTrue("Archive DAT matching must only read its existing prefix/index", source.bytesRead < 1024L * 1024)
        }
        val archive = Files.createTempFile("ambient-headered-snes", ".7z").toFile()
        try {
            SevenZOutputFile(archive).use { output ->
                output.putArchiveEntry(SevenZArchiveEntry().apply { name = "opaque.smc"; size = rom.size.toLong() })
                output.write(rom)
                output.closeArchiveEntry()
            }
            val source = Bytes(archive.readBytes())
            val fastMetadata = GameArchives.probe("opaque.7z", source)!!
            // This archive exposes only a folder CRC in commons-compress, not an entry CRC.
            // Ordinary browsing must not decompress the full ROM to obtain one.
            assertNull(fastMetadata.crc32)
            assertNull(fastMetadata.datCrc32)
            assertTrue(source.bytesRead < 1024L * 1024)
            val explicit = Bytes(archive.readBytes())
            val metadata = GameArchives.probe("opaque.7z", explicit, checksumSystems = setOf("snes"))!!
            assertEquals(crc(rom), metadata.crc32)
            assertEquals(crc(payload), metadata.datCrc32)
            assertTrue("Only an explicit DAT scan streams the missing CRC", explicit.bytesRead > source.bytesRead)
            assertTrue(explicit.bytesRead < 3L * 1024 * 1024)
        } finally { archive.delete() }
    }

    @Test fun smallSevenZipRomsUseAnAlreadyCompletePrefixForMissingChecksums() {
        val rom = ByteArray(32 * 1024 + 512).also(Random(23)::nextBytes)
        val archive = Files.createTempFile("ambient-small-headered-snes", ".7z").toFile()
        try {
            SevenZOutputFile(archive).use { output ->
                output.putArchiveEntry(SevenZArchiveEntry().apply { name = "small.smc"; size = rom.size.toLong() })
                output.write(rom)
                output.closeArchiveEntry()
            }
            val source = Bytes(archive.readBytes())
            val metadata = GameArchives.probe("small.7z", source)!!
            val raw = CRC32().apply { update(rom) }.value.toString(16).uppercase().padStart(8, '0')
            val payload = CRC32().apply { update(rom, 512, rom.size - 512) }.value.toString(16).uppercase().padStart(8, '0')
            assertEquals(raw, metadata.crc32)
            assertEquals(payload, metadata.datCrc32)
            assertTrue(source.bytesRead < 128L * 1024)
        } finally { archive.delete() }
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

    @Test fun zstandardZipEntriesCanBeListedButAreNotOpened() {
        val rom = "rom".toByteArray()
        // A Zstandard frame containing the three bytes above.
        val frame = Base64.getDecoder().decode("KLUv/QRYGQAAcm9tgdGXcA==")
        val folder = Files.createTempDirectory("ambient-game-zstd").toFile()
        try {
            for (method in listOf(ZipMethod.ZSTD, ZipMethod.ZSTD_DEPRECATED)) {
                val archive = File(folder, "${method.name}.zip")
                ZipArchiveOutputStream(archive).use { output ->
                    val entry = ZipArchiveEntry("game.gba").apply {
                        this.method = method.code
                        size = rom.size.toLong()
                        compressedSize = frame.size.toLong()
                        crc = CRC32().apply { update(rom) }.value
                    }
                    output.addRawArchiveEntry(entry, ByteArrayInputStream(frame))
                }
                val media = Bytes(archive.readBytes())
                val output = File(folder, "${method.name}-output")
                assertEquals(listOf("game.gba"), GameArchives.names(archive.name, media))
                assertNull(GameArchives.probe(archive.name, media))
                assertNull(GameArchives.stageSingle(archive.name, media, output, 1024) { })
                assertThrows(GameArchiveException::class.java) { GameArchives.extract(archive, output) }
                assertTrue(output.walkTopDown().none(File::isFile))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun sevenZipFirstEntryHeaderAndSingleRomStaging() {
        val rom = gba("KIRBYMIRROR", 140_000)
        val archiveFile = Files.createTempFile("ambient-rom", ".7z").toFile()
        val folder = Files.createTempDirectory("ambient-game-seven").toFile()
        try {
            SevenZOutputFile(archiveFile).use { output ->
                val entry = SevenZArchiveEntry().apply {
                    name = "hidden.gba"
                    size = rom.size.toLong()
                }
                output.putArchiveEntry(entry)
                output.write(rom)
                output.closeArchiveEntry()
            }
            val media = Bytes(archiveFile.readBytes())
            val meta = GameArchives.probe("collection.7z", media)
            assertEquals("gba", meta?.system?.id)
            assertEquals("KIRBYMIRROR", meta?.internalTitle)
            val staged = GameArchives.stageSingle("collection.7z",
                Bytes(archiveFile.readBytes()), folder, 1024L * 1024) { }
            assertNotNull(staged)
            assertArrayEquals(rom, staged!!.readBytes())
        } finally {
            archiveFile.delete()
            folder.deleteRecursively()
        }
    }

    @Test fun gzipTitleAndDirectStreamRequireNoArchiveCopy() {
        val rom = gba("METROIDFUS", 90_000)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(rom) }
        val metadata = GameArchives.probe("game.gba.gz", Bytes(output.toByteArray()))
        assertEquals("gba", metadata?.system?.id)
        assertEquals("METROIDFUS", metadata?.internalTitle)
        val crc = CRC32().apply { update(rom) }.value
            .toString(16).uppercase().padStart(8, '0')
        assertEquals("GZIP footer should match DAT without full hashing", crc, metadata?.crc32)
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

    @Test fun assignedFoldersFilterByTheSelectedInnerRomNotTheArchiveSuffix() {
        val metadata = GameArchives.probe("Games.zip", Bytes(zip("Mario.gba" to gba("MARIO"))))!!
        assertNull(GameSystems.forAssignedFile(metadata.innerName!!, "snes", metadata.system))
        assertEquals("gba", GameSystems.forAssignedFile(metadata.innerName, "gba", metadata.system)?.id)
        assertNull(GameArchives.probe("Notes.zip", Bytes(zip("notes.txt" to byteArrayOf(1)))))
        assertNull(GameArchives.probe("Bios.zip", Bytes(zip("firmware/bios_E.sms" to byteArrayOf(1)))))
    }

    @Test fun packedDiscHeaderRemainsAuthoritativeAndDescriptorsKeepAssignedSystem() {
        val wii = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN).putInt(0x18, 0x5D1C9EA3).array()
        val metadata = GameArchives.probe("Disc.zip", Bytes(zip("Disc.iso" to wii)))!!
        assertEquals("wii", metadata.system?.id)
        assertTrue(metadata.systemFromHeader)
        assertNull(GameSystems.forAssignedFile(metadata.innerName!!, "snes", metadata.system))
        assertNull(GameSystems.forAssignedFile(metadata.innerName, "gamecube", metadata.system, metadata.systemFromHeader))
        assertEquals("wii", GameSystems.forAssignedFile(metadata.innerName, "wii", metadata.system, metadata.systemFromHeader)?.id)

        val cue = GameArchives.probe("Saturn.zip", Bytes(zip("Disc.cue" to "FILE track.bin BINARY".toByteArray())))!!
        assertEquals("saturn", GameSystems.forAssignedFile(cue.innerName!!, "saturn", cue.system)?.id)
        assertNull(GameSystems.forAssignedFile(cue.innerName, "snes", cue.system))
    }

    @Test fun explicitRawBinArchiveIsRecognizedButCueTracksAreNeverStagedAlone() {
        val raw = zip("Sonic.bin" to ByteArray(512))
        assertNull(GameArchives.probe("Sonic.zip", Bytes(raw)))
        assertEquals("megadrive", GameArchives.probe("Sonic.zip", Bytes(raw), "megadrive")?.system?.id)
        assertNull(GameArchives.probe("Sonic.zip", Bytes(raw), "snes"))
        val folder = Files.createTempDirectory("ambient-disc-tracks").toFile()
        try {
            val tracks = zip("disc.cue" to "FILE track.bin BINARY".toByteArray(), "track.bin" to ByteArray(512))
            assertNull(GameArchives.stageSingle("Disc.zip", Bytes(tracks), folder,
                1024L * 1024, systemId = "psx") { })
            val staged = GameArchives.stageSingle("Sonic.zip", Bytes(raw), folder,
                1024L * 1024, systemId = "megadrive") { }
            assertEquals("Sonic.bin", staged?.name)
        } finally { folder.deleteRecursively() }
    }

    @Test fun mixedConsoleArchiveSelectsOnlyTheAssignedSystemsContent() {
        val archive = zip("a-first.gba" to gba("ADVANCE"), "z-second.nes" to ByteArray(512))
        assertEquals("a-first.gba", GameArchives.probe("Mixed.zip", Bytes(archive))?.innerName)
        assertEquals("z-second.nes", GameArchives.probe("Mixed.zip", Bytes(archive), "nes")?.innerName)
        assertNull(GameArchives.probe("Mixed.zip", Bytes(archive), "snes"))
    }
}
