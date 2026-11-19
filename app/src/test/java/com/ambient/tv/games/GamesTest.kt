package com.ambient.tv.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GamesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun systemsAreFoundByExtension() {
        assertEquals("snes", GameSystems.forFile("Zelda.SFC")?.id)
        assertEquals("gba", GameSystems.forFile("a.gba")?.id)
        assertEquals("psx", GameSystems.forFile("Disc 1.cue")?.id)
        assertNull(GameSystems.forFile("movie.mkv"))
        assertTrue(GameSystems.isGame("pack.zip"))
        assertFalse(GameSystems.isGame("song.mp3"))
        // Every extension belongs to one system only.
        val extensions = GameSystems.all.flatMap { it.extensions }
        assertEquals(extensions.size, extensions.toSet().size)
    }

    @Test fun shaderFollowsSystemThenAllGames() {
        val snes = GameSystems.byId("snes")!!
        val gba = GameSystems.byId("gba")!!
        var state = GameSettingsState()
        assertEquals(GameShader.SHARP, state.shader(snes))
        state = state.withShader(null, GameShader.CRT).withShader(gba, GameShader.LCD)
        assertEquals(GameShader.CRT, state.shader(snes))
        assertEquals(GameShader.LCD, state.shader(gba))
        assertNull(state.ownShader(snes))
        assertEquals(GameShader.CRT, state.withShader(gba, null).shader(gba))
    }

    @Test fun coreOptionsArePerCore() {
        val nes = GameSystems.byId("nes")!!.core
        val gb = GameSystems.byId("gb")!!.core
        val state = GameSettingsState().withCoreOption(nes, "nestopia_region", "PAL").withCoreOption(gb, "gambatte_palette", "GB")
        assertEquals(mapOf("nestopia_region" to "PAL"), state.coreOptions(nes))
        assertEquals(emptyMap<String, String>(), state.withoutCoreOptions(nes).coreOptions(nes))
        assertEquals(mapOf("gambatte_palette" to "GB"), state.withoutCoreOptions(nes).coreOptions(gb))
    }

    @Test fun coreOptionDescriptionsAreParsed() {
        val option = GameCoreOption.parse("region", "Region; Auto|NTSC|PAL", "PAL")!!
        assertEquals("Region", option.title)
        assertEquals(listOf("Auto", "NTSC", "PAL"), option.values)
        assertEquals("PAL", option.value)
        assertEquals("Auto", GameCoreOption.parse("region", "Region; Auto|NTSC", "bogus")!!.value)
        assertNull(GameCoreOption.parse("x", "No choices", null))
        assertNull(GameCoreOption.parse("x", null, null))
    }

    @Test fun cueAndM3uNameTheirFiles() {
        val cue = """
            FILE "Game (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
            FILE track2.bin BINARY
        """.trimIndent()
        assertEquals(listOf("Game (Track 1).bin", "track2.bin"), GameRomPreparer.cueFiles(cue))
        assertEquals(listOf("Disc 1.cue", "Disc 2.cue"), GameRomPreparer.m3uFiles("#EXTM3U\nDisc 1.cue\n\nDisc 2.cue\n"))
        assertEquals("a_b.sfc", GameRomPreparer.safeName("dir/a:b.sfc"))
    }

    private fun elf(align: Long): File {
        val buffer = ByteBuffer.allocate(0x40 + 0x38).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        buffer.putLong(0x20, 0x40) // e_phoff
        buffer.putShort(0x36, 0x38) // e_phentsize
        buffer.putShort(0x38, 1) // e_phnum
        buffer.putInt(0x40, 1) // PT_LOAD
        buffer.putLong(0x40 + 0x30, align)
        return folder.newFile().also { it.writeBytes(buffer.array()) }
    }

    @Test fun coresMustFitTheDevicePageSize() {
        GameCoreManager.checkElf(elf(16_384), 16_384)
        GameCoreManager.checkElf(elf(4_096), 4_096)
        try {
            GameCoreManager.checkElf(elf(4_096), 16_384)
            fail("4 KB aligned core accepted on a 16 KB device")
        } catch (expected: GameCoreIncompatibleException) {
        }
        try {
            GameCoreManager.checkElf(folder.newFile().also { it.writeText("not a library, just text") }, 4_096)
            fail("text accepted as a core")
        } catch (expected: GameCoreIncompatibleException) {
        }
    }
}
