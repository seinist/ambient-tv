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

    @Test fun pinnedCoresAndBuildbotOnlyCoresAreSeparated() {
        val json = File("../cores/cores.json").takeIf { it.isFile } ?: File("cores/cores.json")
        val pinnedIds = Regex(""""core"\s*:\s*"([^"]+)"""")
            .findAll(json.readText()).map { it.groupValues[1] }.toSet()
        val registeredIds = GameSystems.cores.map { it.id }.toSet()
        assertTrue("All own-build cores must be registered", registeredIds.containsAll(pinnedIds))
        assertTrue("The original pinned core catalog must remain available", pinnedIds.size >= 26)
        // New systems are buildbot-first: requiring their own signed builds would be misleading.
        assertFalse("PPSSPP is not yet pinned in cores.json", "ppsspp" in pinnedIds)
        assertFalse("VICE is not yet pinned in cores.json", "vice_x64sc" in pinnedIds)
        assertFalse("Cemu is not yet pinned in cores.json", "cemu" in pinnedIds)
    }

    @Test fun netplayInputSurvivesTheWire() {
        val input = NetplayInput(0x0F0F, 0x7fff, -0x7fff, 123, -456)
        val bytes = ByteArrayOutputStream().also { input.write(DataOutputStream(it)) }.toByteArray()
        assertEquals(input, NetplayInput.read(DataInputStream(ByteArrayInputStream(bytes))))
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

    @Test fun flattenedArchiveNamesCannotClobberEachOther() {
        val zip = folder.newFile("duplicate.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf("A/game.sfc", "B/game.sfc")) {
                out.putNextEntry(ZipEntry(name))
                out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
            }
        }
        assertTrue(runCatching {
            GameArchives.extract(zip, File(folder.root, "duplicate-output"))
        }.exceptionOrNull() is GameArchiveException)
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
