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
        assertEquals(listOf("Auto", "NTSC", "PAL"), option.values.map { it.value })
        assertEquals("PAL", option.value)
        assertEquals("Auto", GameCoreOption.parse("region", "Region; Auto|NTSC", "bogus")!!.value)
        assertNull(GameCoreOption.parse("x", "No choices", null))
        assertNull(GameCoreOption.parse("x", null, null))
    }

    @Test fun gameOptionsOverrideCoreOptions() {
        val n64 = GameSystems.byId("n64")!!.core
        var state = GameSettingsState()
            .withCoreOption(n64, "res", "640x480")
            .withCoreOption(n64, "filter", "on")
            .withGameCoreOption(n64, "game1", "res", "1280x960")
        assertEquals(mapOf("res" to "1280x960", "filter" to "on"), state.effectiveCoreOptions(n64, "game1"))
        assertEquals(mapOf("res" to "640x480", "filter" to "on"), state.effectiveCoreOptions(n64, "game2"))
        assertEquals(GameOptionOrigin.GAME, state.coreOptionOrigin(n64, "game1", "res"))
        assertEquals(GameOptionOrigin.CORE, state.coreOptionOrigin(n64, "game2", "res"))
        assertEquals(GameOptionOrigin.DEFAULT, state.coreOptionOrigin(n64, "game1", "other"))
        // A value for all games replaces this game's own one.
        state = state.withCoreOption(n64, "res", "320x240", gameKey = "game1")
        assertEquals("320x240", state.effectiveCoreOptions(n64, "game1")["res"])
        state = state.withGameCoreOption(n64, "game1", "filter", "off").withoutGameCoreOptions(n64, "game1")
        assertEquals(mapOf("res" to "320x240", "filter" to "on"), state.effectiveCoreOptions(n64, "game1"))
        // Removing a core's options leaves games of other cores alone.
        assertEquals(emptyMap<String, String>(), state.withoutCoreOptions(n64).coreOptions(n64))
    }

    @Test fun pictureRewindAndGraphicsSettings() {
        val snes = GameSystems.byId("snes")!!
        val n64 = GameSystems.byId("n64")!!
        var state = GameSettingsState()
        assertEquals(GameAspect.CORE, state.aspect(snes))
        assertFalse(state.integerScaling(snes))
        assertTrue(state.rewind)
        assertFalse(state.vulkan(n64.core))
        state = state.withAspect(snes, GameAspect.FOUR_THREE).withIntegerScaling(snes, true).withRewind(false)
            .withVulkan(n64.core, true).withVulkan(snes.core, true)
        assertEquals(GameAspect.FOUR_THREE, state.aspect(snes))
        assertEquals(GameAspect.CORE, state.aspect(n64))
        assertTrue(state.integerScaling(snes))
        assertFalse(state.rewind)
        assertTrue(state.vulkan(n64.core))
        // Only cores that render on the GPU have a graphics interface to choose.
        assertFalse(state.vulkan(snes.core))
        assertEquals(GameAspect.CORE, state.withAspect(snes, GameAspect.CORE).aspect(snes))
        assertFalse("aspect.snes" in state.withAspect(snes, GameAspect.CORE).values)
    }

    @Test fun coreOptionsV2MetadataIsParsed() {
        val json = """{"v":2,"title":"Interne Auflösung","info":"Höher ist schärfer.","category":"video",""" +
            """"categoryTitle":"Video","categoryInfo":"Bild","default":"640x480","visible":false,""" +
            """"values":[{"value":"320x240","label":"320×240 (nativ)"},{"value":"640x480","label":""}]}"""
        val option = GameCoreOption.parse("res", json, "320x240")!!
        assertEquals("Interne Auflösung", option.title)
        assertEquals("Höher ist schärfer.", option.info)
        assertEquals(GameCoreCategory("video", "Video", "Bild"), option.category)
        assertEquals(listOf(GameCoreValue("320x240", "320×240 (nativ)"), GameCoreValue("640x480", "640x480")), option.values)
        assertEquals("320x240", option.value)
        assertEquals("320×240 (nativ)", option.label)
        assertEquals("640x480", option.default)
        assertFalse(option.visible)
        // An unknown stored value falls back to the core's default.
        assertEquals("640x480", GameCoreOption.parse("res", json, "bogus")!!.value)
        assertNull(GameCoreOption.parse("res", "{broken", null))
        assertNull(GameCoreOption.parse("res", """{"title":"x","values":[]}""", null))
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
