package com.ambient.tv.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class GameSystemAssignmentTest {
    @Test fun explicitSystemAssignmentRejectsForeignCartridgesDiscsAndComputerFiles() {
        listOf(
            "Mario.smc" to "psx",
            "Mario.gba" to "snes",
            "Sonic.md" to "nes",
            "Game.iso" to "snes",
            "Game.chd" to "gb",
            "Disk.d64" to "psx",
            "Game.pbp" to "wii",
            "Game.gdi" to "saturn",
            "Game.cue" to "ps2",
            "Game.m3u" to "gba",
            "notes.txt" to "snes",
            "unknown.zip" to "snes",
        ).forEach { (name, id) ->
            assertNull("$name must not be offered for $id", GameSystems.forAssignedFile(name, id))
        }
        assertEquals("snes", GameSystems.forAssignedFile("Mario.SMC", "snes")?.id)
    }

    @Test fun sharedFormatsUseTheExplicitAssignmentInsteadOfTheirGenericDefault() {
        listOf(
            "Disc.cue" to "saturn",
            "Disc.chd" to "dreamcast",
            "Disc.chd" to "psp",
            "Game.pbp" to "psp",
            "Game.iso" to "ps2",
            "Game.rvz" to "gamecube",
            "Game.ciso" to "wii",
            "Disk.d64" to "c128",
            "Program.prg" to "vic20",
            "Disk.nib" to "plus4",
            "Disks.m3u" to "c64",
        ).forEach { (name, id) ->
            assertEquals("$name should resolve within $id", id, GameSystems.forAssignedFile(name, id)?.id)
        }
    }

    @Test fun verifiedNintendoDiscHeaderRejectsWrongAssignments() {
        val header = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x18, 0x5D1C9EA3).array()
        val actual = GameSystems.forSourceFile("Disc.iso", "/PSP/Disc.iso", header)
        assertEquals("wii", actual?.id)
        assertNull(GameSystems.forAssignedFile("Disc.iso", "gamecube", actual, systemFromHeader = true))
        assertNull(GameSystems.forAssignedFile("Disc.iso", "psp", actual, systemFromHeader = true))
        assertEquals("wii", GameSystems.forAssignedFile("Disc.iso", "auto", actual, systemFromHeader = true)?.id)
        assertEquals("wii", GameSystems.forAssignedFile("Disc.iso", "wii", actual, systemFromHeader = true)?.id)
        val hint = GameSystems.forSourceFile("Disc.iso", "/Wii/Disc.iso")
        assertEquals("ps2", GameSystems.forAssignedFile("Disc.iso", "ps2", hint)?.id)
    }

    @Test fun folderHintsCannotConvertAComputerDiskIntoAConsoleDisc() {
        assertEquals("c64", GameSystems.forSourceFile("Disk.d64", "/PSP/Disk.d64")?.id)
        assertEquals("psx", GameSystems.forSourceFile("Disc.chd", "/C128/Disc.chd")?.id)
        assertEquals("c128", GameSystems.forSourceFile("Disk.nib", "/C128/Disk.nib")?.id)
        assertEquals("ps2", GameSystems.forSourceFile("Disc.cso", "/PS2/Disc.cso")?.id)
    }

    @Test fun firmwareIsNotPlayableContentEvenInsideAnArchiveFolder() {
        val system = GameSystems.byId("megadrive")!!
        assertFalse(GameSystems.acceptsFile(system, "bios_E.sms"))
        assertFalse(GameSystems.acceptsFile(system, "firmware/bios_E.sms"))
        assertTrue(GameSystems.acceptsFile(system, "Sonic.sms"))
    }

    @Test fun systemsAreAlphabeticalByTheirDisplayedNames() {
        val names = GameSystems.alphabetical.map { it.name.lowercase(Locale.ROOT) }
        assertEquals(names.sorted(), names)
        assertEquals(GameSystems.all.size, GameSystems.alphabetical.size)
        assertEquals(listOf("c64", "gba", "nes", "psx", "snes"),
            GameSystems.sortIdsByName(listOf("snes", "psx", "gba", "c64", "nes", "snes", "unknown")))
    }

    @Test fun rawBinNeedsAnExplicitCompatibleSystemAndNeverGuessesFromItsName() {
        assertFalse(GameSystems.isGame("Sonic.bin"))
        assertNull(GameSystems.forAssignedFile("Sonic.bin", "auto"))
        assertEquals("megadrive", GameSystems.forAssignedFile("Sonic.bin", "megadrive")?.id)
        assertEquals("a2600", GameSystems.forAssignedFile("Pitfall.bin", "a2600")?.id)
        assertNull(GameSystems.forAssignedFile("Sonic.bin", "snes"))
        assertNull(GameSystems.forAssignedFile("gb_bios.bin", "megadrive"))
    }
}
