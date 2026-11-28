package com.ambient.tv.games

import com.ambient.tv.ui.games.NetplayInput
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameArchivesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun discsStartFromTheirPlaylistOrCueSheet() {
        assertEquals("game.cue", GameArchives.choose(listOf("track01.bin", "game.cue", "readme.txt")) { it })
        assertEquals("game.m3u", GameArchives.choose(listOf("d1.cue", "game.m3u", "d1.bin")) { it })
        assertEquals("rom.sfc", GameArchives.choose(listOf("sub/x.nes", "rom.sfc")) { it })
        assertNull(GameArchives.choose(listOf("readme.txt", "cover.png")) { it })
    }

    @Test fun zipIsUnpackedCompletely() {
        val zip = folder.newFile("disc.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Game/game.cue")); out.write("FILE \"track.bin\" BINARY".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("Game/track.bin")); out.write(ByteArray(1000)); out.closeEntry()
            out.putNextEntry(ZipEntry("Game/")); out.closeEntry()
        }
        val files = GameArchives.extract(zip, File(folder.root, "out"))
        assertEquals(setOf("game.cue", "track.bin"), files.map { it.name }.toSet())
        assertEquals("game.cue", GameArchives.choose(files) { it.name }?.name)
        assertEquals(1000L, files.first { it.name == "track.bin" }.length())
    }

    @Test fun sevenZipAndGzipAreUnpacked() {
        val seven = folder.newFile("rom.7z")
        SevenZOutputFile(seven).use { out ->
            val source = folder.newFile("Zelda.sfc").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
            out.putArchiveEntry(out.createArchiveEntry(source, "Zelda.sfc"))
            out.write(source.readBytes())
            out.closeArchiveEntry()
        }
        val files = GameArchives.extract(seven, File(folder.root, "seven"))
        assertEquals(listOf("Zelda.sfc"), files.map { it.name })
        assertEquals(2048L, files.single().length())

        val gz = folder.newFile("Sonic.md.gz")
        GZIPOutputStream(gz.outputStream()).use { it.write(ByteArray(512)) }
        assertEquals(listOf("Sonic.md"), GameArchives.extract(gz, File(folder.root, "gz")).map { it.name })
        assert(GameSystems.isGame("Zelda.7z") && GameSystems.isGame("Pack.rar") && GameSystems.isGame("Sonic.md.gz"))
    }

    @Test fun rar4StoredEntryAndOutputLimitRemainCompatible() {
        // Minimal RAR4 fixture: stored game.sfc, valid file/header CRCs.
        val fixture = "526172211a0700cf907300000d000000000000003bec74008028000600000006000000032477f6810000215814300800a481000067616d652e73666301020304050604b07b00000700"
        val rar = folder.newFile("game.rar").apply {
            writeBytes(fixture.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        val files = GameArchives.extract(rar, File(folder.root, "rar-output"))
        assertEquals(listOf("game.sfc"), files.map { it.name })
        org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), files.single().readBytes())
        assertTrue(runCatching {
            GameArchives.extract(rar, File(folder.root, "rar-limited"), maxOutputBytes = 5)
        }.exceptionOrNull() is GameArchiveException)
    }

    @Test fun rar5StillReportsUnsupportedWithoutWritingFiles() {
        val rar = folder.newFile("rar5.rar").apply {
            writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00))
        }
        val out = File(folder.root, "rar5-output")
        val error = runCatching { GameArchives.extract(rar, out) }.exceptionOrNull()
        assertTrue(error is GameArchiveException)
        assertTrue(error!!.message!!.contains("RAR5 is not supported"))
        assertTrue(out.listFiles().orEmpty().isEmpty())
    }

    @Test fun coresAreChosenPerGameOverSystemOverDefault() {
        val snes = GameSystems.byId("snes")!!
        var state = GameSettingsState()
        assertEquals("snes9x", state.gameCore(snes, "g1").id)
        state = state.withSystemCore(snes, snes.core("bsnes")!!)
        assertEquals("bsnes", state.gameCore(snes, "g1").id)
        state = state.withGameCore(snes, "g1", snes.core("snes9x2010"))
        assertEquals("snes9x2010", state.gameCore(snes, "g1").id)
        assertEquals("bsnes", state.gameCore(snes, "g2").id)
        // Cores of other systems are not accepted.
        assertEquals("bsnes", state.withGameCore(snes, "g2", GameSystems.byId("nes")!!.core).gameCore(snes, "g2").id)
        assertNull(state.withSystemCore(snes, snes.core).values["syscore.snes"])
        // mGBA plays both Game Boy systems.
        assert(GameSystems.byId("gb")!!.core("mgba") != null && GameSystems.byId("gba")!!.core.id == "mgba")
        assertEquals(GameSystems.cores.size, GameSystems.cores.map { it.id }.toSet().size)
    }

    @Test fun pinnedCatalogCoversEveryRegisteredCore() {
        val json = File("../cores/cores.json").takeIf { it.isFile } ?: File("cores/cores.json")
        val pinnedIds = Regex(""""core"\s*:\s*"([^"]+)"""")
            .findAll(json.readText()).map { it.groupValues[1] }.toList()
        val registeredIds = GameSystems.cores.map { it.id }.toSet()
        val pinnedSet = pinnedIds.toSet()
        assertEquals("Each core must have exactly one pinned build recipe", pinnedSet.size, pinnedIds.size)
        assertEquals("Unregistered pinned cores: ${pinnedSet - registeredIds}; missing pinned recipes: ${registeredIds - pinnedSet}",
            registeredIds, pinnedSet)
        assertTrue("The expanded 50-core catalog must remain available", pinnedSet.size >= 50)
        // The catalog contains build recipes, not proof of a published signed artifact.
        // Runtime availability and buildbot fallback are tested separately.
    }

    @Test fun netplayInputSurvivesTheWire() {
        val input = NetplayInput(0x0F0F, 0x7fff, -0x7fff, 123, -456, 8192, 24575)
        val bytes = ByteArrayOutputStream().also { input.write(DataOutputStream(it)) }.toByteArray()
        assertEquals(input, NetplayInput.read(DataInputStream(ByteArrayInputStream(bytes))))
        assertEquals(16, bytes.size)
        assertEquals(1f, NetplayInput.axis(0x7fff))
        assertEquals(-1f, NetplayInput.axis(-0x8000))
        assertEquals(0f, NetplayInput.axis(0))
    }
    @Test fun decompressionLimitCountsActualBytesNotOnlyDeclaredArchiveSizes() {
        val zip = folder.newFile("limited.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("game.sfc"))
            out.write(ByteArray(4096))
            out.closeEntry()
        }
        assertTrue(runCatching {
            GameArchives.extract(zip, File(folder.root, "limited-zip"), maxOutputBytes = 1024)
        }.exceptionOrNull() is GameArchiveException)

        val gzip = folder.newFile("limited.sfc.gz")
        GZIPOutputStream(gzip.outputStream()).use { it.write(ByteArray(4096)) }
        assertTrue(runCatching {
            GameArchives.extract(gzip, File(folder.root, "limited-gzip"), maxOutputBytes = 1024)
        }.exceptionOrNull() is GameArchiveException)
    }

    @Test fun nestedArchiveDiscTracksKeepTheirPathsAndRejectTraversal() {
        val zip = folder.newFile("nested.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf("A/game.sfc", "B/game.sfc", "Game/disc.cue", "Game/tracks/track.bin")) {
                out.putNextEntry(ZipEntry(name))
                out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
            }
        }
        val extracted = GameArchives.extract(zip, File(folder.root, "nested-output"))
        assertEquals(4, extracted.size)
        assertTrue(File(folder.root, "nested-output/A/game.sfc").isFile)
        assertTrue(File(folder.root, "nested-output/B/game.sfc").isFile)
        assertTrue(File(folder.root, "nested-output/Game/tracks/track.bin").isFile)

        val unsafe = folder.newFile("traversal.zip")
        ZipOutputStream(unsafe.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("../escape.sfc"))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
        assertTrue(runCatching {
            GameArchives.extract(unsafe, File(folder.root, "unsafe-output"))
        }.exceptionOrNull() is GameArchiveException)
        assertFalse(File(folder.root, "escape.sfc").exists())
    }

    @Test fun cachedDiscSidecarsAreValidatedIncludingNestedCueTracks() {
        val root = folder.newFolder("cached-disc")
        val playlist = File(root, "game.m3u").apply { writeText("disc/disc.cue") }
        val cue = File(root, "disc/disc.cue").also { it.parentFile!!.mkdirs() }
        cue.writeText("""FILE "tracks/data.bin" BINARY""")
        assertFalse(GameRomPreparer.cachedCompanionsComplete(playlist))
        File(root, "disc/tracks/data.bin").also {
            it.parentFile!!.mkdirs()
            it.writeBytes(byteArrayOf(42))
        }
        assertTrue(GameRomPreparer.cachedCompanionsComplete(playlist))
        assertEquals("disc/tracks/data.bin", GameRomPreparer.safeRelativePath("disc\\tracks/data.bin"))
        assertEquals(null, GameRomPreparer.safeRelativePath("../escape.bin"))
        assertEquals(null, GameRomPreparer.safeRelativePath("/absolute/game.cue"))
        assertEquals(null, GameRomPreparer.safeRelativePath("C:/disc.bin"))
    }

    @Test fun netplayRejectsOutOfWindowAndNegativeFrameNumbers() {
        assertTrue(com.ambient.tv.ui.games.GameNetplay.acceptableFrame(3, 0))
        assertTrue(com.ambient.tv.ui.games.GameNetplay.acceptableFrame(64, 0))
        assertFalse(com.ambient.tv.ui.games.GameNetplay.acceptableFrame(65, 0))
        assertFalse(com.ambient.tv.ui.games.GameNetplay.acceptableFrame(-1, 0))
        assertFalse(com.ambient.tv.ui.games.GameNetplay.acceptableFrame(99, 100))
        assertTrue(com.ambient.tv.ui.games.GameNetplay.acceptableFrame(105, 100))
    }

}
