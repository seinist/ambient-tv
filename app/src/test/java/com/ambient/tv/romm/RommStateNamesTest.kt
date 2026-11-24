package com.ambient.tv.romm

import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.games.GameStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommStateNamesTest {
    private val stamp = "sha256:" + "0123456789abcdef".repeat(4)

    @Test fun aStateKeepsItsIdentityOnTheServer() {
        val entry = GameStateEntry("m1764000000000", GameStateKind.MANUAL, 1_764_000_000_000, "snes9x", stamp, 7, "Boss")
        val name = RommStateNames.fileName(entry)
        assertTrue(name, name.startsWith("ambient-m1764000000000.") && name.endsWith(".state"))
        assertEquals(entry, RommStateNames.parse(name, stamp))
    }

    @Test fun anotherCoreBuildIsMarkedSoItIsNeverLoadedBlindly() {
        val entry = GameStateEntry("auto", GameStateKind.AUTO, 5, "snes9x", stamp)
        val other = "sha256:" + "f".repeat(64)
        val parsed = RommStateNames.parse(RommStateNames.fileName(entry), other)!!
        assertNotEquals(other, parsed.coreStamp)
        assertTrue(!GameStateStore.compatible(parsed, "snes9x", other, "legacy"))
        assertTrue(GameStateStore.compatible(RommStateNames.parse(RommStateNames.fileName(entry), stamp)!!, "snes9x", stamp, "legacy"))
    }

    @Test fun longLabelsAreShortenedToAValidFileName() {
        val entry = GameStateEntry("m1", GameStateKind.MANUAL, 1, "mupen64plus_next_gles3", stamp, 50, "Ä".repeat(80))
        val name = RommStateNames.fileName(entry)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        val parsed = RommStateNames.parse(name, stamp)!!
        assertEquals(entry.copy(label = parsed.label), parsed)
        assertTrue(parsed.label!!.length < 80)
    }

    @Test fun otherClientsFilesAreIgnored() {
        assertNull(RommStateNames.parse("Super Mario World.state", stamp))
        assertNull(RommStateNames.parse("ambient-auto.notbase64!.state", stamp))
        assertNull(RommStateNames.parse("ambient-x1.YWJjZGVmZ2g.state", stamp))
        // The kind in the metadata must match the id.
        val auto = RommStateNames.fileName(GameStateEntry("auto", GameStateKind.AUTO, 1, "snes9x", stamp))
        assertNull(RommStateNames.parse(auto.replace("ambient-auto.", "ambient-undo."), stamp))
    }

    @Test fun serverFileNamesStayPlainNames() {
        assertEquals("scph5501.bin", RommFiles.safeName("scph5501.bin"))
        assertEquals("scph5501.bin", RommFiles.safeName("bios/scph5501.bin"))
        assertNull(RommFiles.safeName(".."))
        assertNull(RommFiles.safeName(".hidden"))
        assertNull(RommFiles.safeName("a:b"))
        assertTrue(RommFiles.isSram("Game (USA).srm"))
        assertTrue(!RommFiles.isSram("Game.state"))
    }
}
