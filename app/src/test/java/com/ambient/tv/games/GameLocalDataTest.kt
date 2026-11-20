package com.ambient.tv.games

import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.files.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GameLocalDataTest {
    private val storage = GameStorage(ApplicationProvider.getApplicationContext())
    private val states = GameStateStore(storage)
    private val snes = GameSystems.byId("snes")!!
    private val game = PreparedGame(
        fileId = "files:src:x", title = "Test", file = File("test.sfc"), system = snes, saveKey = "key-${System.nanoTime()}",
        sourceName = "NAS", entry = FileEntry("src", "test.sfc", "test.sfc", false),
    )

    @Test fun biosFilesAreKnownByNameAndNotOfferedAsGames() {
        assertTrue(GameSystems.isBios("SCPH5501.BIN"))
        assertEquals("psx", GameSystems.bios("scph5501.bin").single().first.id)
        // bios.gg has a Game Gear extension, but it is firmware.
        assertTrue(FileEntry("s", "bios.gg", "bios.gg", false).bios)
        assertFalse(FileEntry("s", "bios.gg", "bios.gg", false).game)
        assertTrue(FileEntry("s", "Sonic.gg", "Sonic.gg", false).game)
    }

    @Test fun requiredBiosDependsOnTheGameFile() {
        val nes = GameSystems.byId("nes")!!.bios.single()
        assertTrue(nes.requiredFor("fds"))
        assertFalse(nes.requiredFor("nes"))
        assertTrue(GameSystems.byId("lynx")!!.bios.single().requiredFor("lnx"))
        assertFalse(GameSystems.byId("psx")!!.bios.first().requiredFor("cue"))
        // Every catalogue checksum is a lower-case MD5.
        GameSystems.all.flatMap { it.bios }.forEach { assertTrue(it.file, it.md5.matches(Regex("[0-9a-f]{32}"))) }
    }

    @Test fun md5OfAFile() {
        val file = File.createTempFile("bios", ".bin").apply { writeText("abc") }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", GameBiosManager.md5(file))
    }

    @Test fun manualStatesAreNumberedAndAutoReplacesItself() {
        states.save(game, GameStateKind.AUTO, byteArrayOf(1), null, "core-a", now = 1)
        states.save(game, GameStateKind.AUTO, byteArrayOf(2), null, "core-a", now = 2)
        val first = states.save(game, GameStateKind.MANUAL, byteArrayOf(3), null, "core-a", now = 3)
        val second = states.save(game, GameStateKind.MANUAL, byteArrayOf(4), null, "core-b", now = 4)
        states.save(game, GameStateKind.UNDO, byteArrayOf(5), null, "core-a", now = 5)
        val list = states.list(game)
        assertEquals(listOf(GameStateKind.AUTO, GameStateKind.UNDO, GameStateKind.MANUAL, GameStateKind.MANUAL), list.map { it.kind })
        assertEquals(listOf(2, 1), list.filter { it.kind == GameStateKind.MANUAL }.map { it.number })
        assertEquals(2, states.read(game, states.entry(game, GameStateKind.AUTO)!!)!!.single().toInt())
        assertEquals(second, states.latestManual(game))
        assertNull(states.screenshot(game, first))
        states.delete(game, second)
        assertEquals(first, states.latestManual(game))
        assertNotNull(states.read(game, first))
    }

    @Test fun manualStatesAreCapped() {
        repeat(GameStateStore.MAX_MANUAL + 3) { index ->
            states.save(game, GameStateKind.MANUAL, byteArrayOf(index.toByte()), null, "c", now = 100L + index)
        }
        val manual = states.list(game).filter { it.kind == GameStateKind.MANUAL }
        assertEquals(GameStateStore.MAX_MANUAL, manual.size)
        assertEquals(GameStateStore.MAX_MANUAL + 3, manual.first().number)
    }
}
