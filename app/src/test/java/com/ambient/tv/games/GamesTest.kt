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

    @Test fun hdrToneMappingRequiresVulkanAndDefaultsOff() {
        val vulkanCore = GameSystems.byId("psx")!!.core("mednafen_psx_hw")!!
        val softwareCore = GameSystems.byId("snes")!!.core
        val nonVulkanCore = GameSystems.byId("saturn")!!.core("yabasanshiro")!!
        val original = GameSettingsState()
        assertEquals(GameHdrToneMap.OFF, original.hdrToneMap(vulkanCore))
        val selected = original.withHdrToneMap(vulkanCore, GameHdrToneMap.PQ)
            .withHdrToneMap(softwareCore, GameHdrToneMap.HLG)
            .withHdrToneMap(nonVulkanCore, GameHdrToneMap.HLG)
        assertEquals(GameHdrToneMap.PQ, selected.hdrToneMap(vulkanCore))
        assertEquals(GameHdrToneMap.OFF, selected.hdrToneMap(softwareCore))
        assertEquals(GameHdrToneMap.OFF, selected.hdrToneMap(nonVulkanCore))
        assertEquals(GameHdrToneMap.OFF, selected.withHdrToneMap(vulkanCore, GameHdrToneMap.OFF).hdrToneMap(vulkanCore))
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

    @Test fun extendedSystemsAndPlatformSpecificCores() {
        assertEquals("nds", GameSystems.forFile("Pokemon.nds")?.id)
        assertEquals("n3ds", GameSystems.forFile("Luigi.3ds")?.id)
        assertEquals("psp", GameSystems.forFile("LocoRoco.cso")?.id)
        assertEquals("wii", GameSystems.forFile("Mario.wbfs")?.id)
        assertEquals("wiiu", GameSystems.forFile("Zelda.wua")?.id)
        assertEquals("dreamcast", GameSystems.forFile("Sonic.gdi")?.id)
        assertEquals("saturn", GameSystems.forFile("Saturn.ccd")?.id)
        assertEquals("c64", GameSystems.forFile("Game.d64")?.id)
        assertEquals("a7800", GameSystems.forFile("Game.a78")?.id)
        assertTrue(GameSystems.isGame("game.iso"))
        assertTrue(GameSystems.isGame("game.rvz"))
        assertEquals("c128", GameSystems.forPreparedFile(folder.newFile("test.d64"), "/NAS/C128/test.d64")?.id)
        assertEquals("vic20", GameSystems.forPreparedFile(folder.newFile("test.prg"), "/NAS/VIC-20/test.prg")?.id)
        assertEquals("saturn", GameSystems.forPreparedFile(folder.newFile("test.chd"), "/NAS/Saturn/test.chd")?.id)
        assertEquals("psp", GameSystems.forPreparedFile(folder.newFile("test.iso"), "/NAS/PSP/test.iso")?.id)
        assertNull(GameSystems.forPreparedFile(folder.newFile("unknown.iso"), "/NAS/unknown.iso"))
        assertTrue(GameSystems.cores.map { it.id }.containsAll(
            listOf("ppsspp", "vice_x64sc", "vice_x128", "vice_xvic", "yabasanshiro", "dolphin", "cemu", "melondsds")))
    }

    @Test fun originalDiscSignaturesWinOverFolderNames() {
        fun iso(name: String, wii: Boolean): File {
            val buf = ByteBuffer.allocate(0x40).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(if (wii) 0x18 else 0x1c, if (wii) 0x5D1C9EA3 else 0xC2339F3DL.toInt())
            return folder.newFile(name).also { it.writeBytes(buf.array()) }
        }
        assertEquals("wii", GameSystems.forPreparedFile(iso("wii.iso", true), "/NAS/PSP/wii.iso")?.id)
        assertEquals("gamecube", GameSystems.forPreparedFile(iso("gc.iso", false), "/NAS/Wii/gc.iso")?.id)
        assertTrue(GameArchives.choose(listOf("game.iso", "notes.txt")) { it } == "game.iso")
    }

    @Test fun coreGraphicsAndFeatureFlagsAreHonored() {
        val psp = GameSystems.byId("psp")!!.core
        val cemu = GameSystems.byId("wiiu")!!.core
        val yaba = GameSystems.byId("saturn")!!.core("yabasanshiro")!!
        var state = GameSettingsState()
        assertTrue(psp.supportsVulkan)
        assertFalse(state.vulkan(psp))
        state = state.withVulkan(psp, true)
        assertTrue(state.vulkan(psp))
        assertTrue(state.vulkan(cemu))
        assertTrue(state.withVulkan(cemu, false).vulkan(cemu))
        assertFalse(cemu.saveStates)
        assertFalse(cemu.netplay)
        assertTrue(yaba.hardware)
        assertFalse(yaba.supportsVulkan)
        assertFalse(state.withVulkan(yaba, true).vulkan(yaba))
    }

    @Test fun dreamcastGdiTrackFilesAreDetected() {
        assertEquals(listOf("track01.bin", "track 02.raw"), GameRomPreparer.gdiFiles(
            "2\n1 0 4 2352 track01.bin 0\n2 4500 0 2352 \"track 02.raw\" 0\n"))
    }

    @Test fun touchscreenSystemsDoNotCaptureEveryTapAsMenuOpen() {
        listOf("nds", "n3ds", "wii", "wiiu").forEach { id ->
            assertTrue("Expected touch input for $id", GameSystems.byId(id)!!.pointerInput)
        }
        listOf("nes", "snes", "c64", "gamecube", "psp").forEach { id ->
            assertFalse("Do not steal menu tap for $id", GameSystems.byId(id)!!.pointerInput)
        }
    }

    @Test fun coreFilesAreRecognisedByName() {
        assertEquals("snes9x", GameSystems.coreForFile("snes9x_libretro_android.so")?.id)
        assertEquals("snes9x", GameSystems.coreForFile("snes9x_libretro_android.so.zip")?.id)
        assertEquals("mgba", GameSystems.coreForFile("MGBA_LIBRETRO.SO")?.id)
        assertEquals("mupen64plus_next_gles3", GameSystems.coreForFile("mupen64plus_next_gles3_libretro_android.so")?.id)
        assertNull(GameSystems.coreForFile("unknown_libretro_android.so"))
        assertNull(GameSystems.coreForFile("snes9x.so"))
        assertNull(GameSystems.coreForFile("snes9x_libretro_android.zip"))
        // A zipped core is not offered as a game archive.
        assertFalse(GameSystems.isGame("snes9x_libretro_android.so.zip"))
        assertTrue(GameSystems.isGame("Zelda.zip"))
        assertEquals(GameSystems.cores.size, GameSystems.cores.map { it.id }.toSet().size)
    }

    @Test fun folderImportsLeaveMediaAndGamesWhereTheyAre() {
        fun entry(name: String) = com.ambient.tv.data.files.FileEntry("s", "/$name", name, directory = false)
        assertTrue(GameDataImporter.wanted(entry("font.ttf")))
        assertTrue(GameDataImporter.wanted(entry("hires_texture.png")))
        assertFalse(GameDataImporter.wanted(entry("Zelda.sfc")))
        assertFalse(GameDataImporter.wanted(entry("movie.mkv")))
        assertFalse(GameDataImporter.wanted(entry(".DS_Store")))
        assertEquals("a_b_c.bin", GameDataImporter.safeName("a/b:c.bin"))
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
