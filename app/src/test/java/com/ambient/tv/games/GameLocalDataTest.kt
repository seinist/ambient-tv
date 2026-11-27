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

    @Test fun coreProfilesShareSramAndStateDirectory() {
        val optimized = game.copy(experimentalProfile = "optimized")
        val a57 = game.copy(experimentalProfile = "optimized-a57")
        try {
            assertEquals(storage.savesDirectory(game), storage.savesDirectory(optimized))
            assertEquals(storage.statesDirectory(game), storage.statesDirectory(a57))
            storage.write(storage.sramFile(game), byteArrayOf(1))
            assertEquals(1, storage.read(storage.sramFile(optimized))!!.single().toInt())
            storage.write(storage.sramFile(a57), byteArrayOf(2))
            assertEquals(2, storage.read(storage.sramFile(game))!!.single().toInt())
            val entry = states.save(game, GameStateKind.MANUAL, byteArrayOf(3), null, "stable", now = 1)
            assertEquals(entry, states.list(optimized).single())
            assertEquals(3, states.read(a57, entry)!!.single().toInt())
            assertTrue(GameStateStore.compatible(entry, entry.coreId, "stable", "legacy"))
            assertTrue(!GameStateStore.compatible(entry, entry.coreId, "different", "legacy"))
        } finally {
            storage.statesDirectory(game).deleteRecursively()
            storage.sramFile(game).delete()
        }
    }

    @Test fun incompatibleAutomaticCheckpointIsPreservedBeforeReplacement() {
        try {
            states.save(game, GameStateKind.AUTO, byteArrayOf(1), null, "stable", now = 1)
            states.save(game.copy(experimentalProfile = "optimized"), GameStateKind.AUTO,
                byteArrayOf(2), null, "optimized", now = 2)
            val archived = states.list(game).single { it.kind == GameStateKind.MANUAL }
            assertEquals("stable", archived.coreStamp)
            assertEquals(1, states.read(game, archived)!!.single().toInt())
            assertEquals("optimized", states.entry(game, GameStateKind.AUTO)!!.coreStamp)
        } finally {
            storage.statesDirectory(game).deleteRecursively()
        }
    }

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

    @Test fun savesInTheSameMillisecondRemainDistinctAndNewestUsesItsSequence() {
        val first = states.save(game, GameStateKind.MANUAL, byteArrayOf(1), null, "c", now = 100)
        val second = states.save(game, GameStateKind.MANUAL, byteArrayOf(2), null, "c", now = 100)
        assertFalse(first.id == second.id)
        assertEquals(2, states.list(game).size)
        assertEquals(second, states.latestManual(game))
        assertEquals(1, states.read(game, first)!!.single().toInt())
        assertEquals(2, states.read(game, second)!!.single().toInt())
    }

    @Test fun labelsSurviveReopenWithoutChangingStateCompatibilityOrBytes() {
        val original = states.save(game, GameStateKind.MANUAL, byteArrayOf(3, 4, 5), null, "build", now = 50)
        val renamed = states.rename(game, original, "  Before the boss  ")
        assertEquals("Before the boss", renamed.label)
        assertEquals(original.copy(label = "Before the boss"), renamed)
        val reopened = GameStateStore(storage)
        assertEquals(renamed, reopened.latestManual(game))
        assertTrue(reopened.read(game, renamed)!!.contentEquals(byteArrayOf(3, 4, 5)))
        assertTrue(GameStateStore.compatible(renamed, game.system.core.id, "build", "legacy"))
        assertNull(reopened.rename(game, renamed, "  ").label)
    }

    @Test fun staleRenameCannotResurrectADeletedSave() {
        val entry = states.save(game, GameStateKind.MANUAL, byteArrayOf(1), null, "c", now = 100)
        states.delete(game, entry)
        org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) { states.rename(game, entry, "Lost") }
        assertTrue(states.list(game).isEmpty())
    }

    @Test fun invalidLabelsAreRejectedWithoutChangingMetadata() {
        val entry = states.save(game, GameStateKind.MANUAL, byteArrayOf(1), null, "c", now = 100)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { states.rename(game, entry, "x".repeat(GameStateStore.MAX_LABEL + 1)) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { states.rename(game, entry, "line\nbreak") }
        assertEquals(entry, states.latestManual(game))
    }
    @Test fun coreFingerprintIgnoresMtimeAndRejectsOtherCores() {
        val file = File.createTempFile("retro-core-", ".so")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val sha = GameStateStore.coreStamp(file)
            val old = GameStateStore.legacyCoreStamp(file)
            assertTrue(sha.startsWith("sha256:"))
            file.setLastModified(file.lastModified() - 30_000L)
            assertEquals(sha, GameStateStore.coreStamp(file))
            assertFalse(old == GameStateStore.legacyCoreStamp(file))
            val entry = GameStateEntry("auto", GameStateKind.AUTO, 1L, "snes9x", sha)
            assertTrue(GameStateStore.compatible(entry, "snes9x", sha, "unused"))
            assertFalse(GameStateStore.compatible(entry.copy(coreId = "bsnes"), "snes9x", sha, old))
            assertFalse(GameStateStore.compatible(entry.copy(coreStamp = "other"), "snes9x", sha, old))
            assertTrue(GameStateStore.compatible(entry.copy(coreStamp = old), "snes9x", sha, old))
        } finally { file.delete() }
    }

}
