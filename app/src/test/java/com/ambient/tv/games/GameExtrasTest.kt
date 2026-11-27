package com.ambient.tv.games

import com.ambient.tv.ui.games.GameNetplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameExtrasTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun retroAchievementsConsolesFollowTheExtension() {
        val md = GameSystems.byId("megadrive")!!
        assertEquals(1, RaConsoles.forGame(md, "md"))
        assertEquals(11, RaConsoles.forGame(md, "sms"))
        assertEquals(15, RaConsoles.forGame(md, "gg"))
        assertEquals(6, RaConsoles.forGame(GameSystems.byId("gb")!!, "gbc"))
        assertEquals(81, RaConsoles.forGame(GameSystems.byId("nes")!!, "fds"))
        assertEquals(12, RaConsoles.forGame(GameSystems.byId("psx")!!, "cue"))
        // Newly registered cores are not automatically supported by our RA console-id mapper.
        val covered = listOf("nes", "snes", "gb", "gba", "megadrive", "pce", "ngp",
            "wswan", "lynx", "a2600", "vb", "n64", "psx")
        covered.forEach { id ->
            val system = GameSystems.byId(id)!!
            system.extensions.forEach { ext ->
                org.junit.Assert.assertNotNull("Missing RA console id: $id .$ext", RaConsoles.forGame(system, ext))
            }
        }
        assertNull(RaConsoles.forGame(GameSystems.byId("wiiu")!!, "wua"))
        assertEquals(30, RaConsoles.forGame(GameSystems.byId("c64")!!, "d64"))
        assertEquals(18, RaConsoles.forGame(GameSystems.byId("nds")!!, "nds"))
        assertEquals(78, RaConsoles.forGame(GameSystems.byId("nds")!!, "dsi"))
        assertEquals(41, RaConsoles.forGame(GameSystems.byId("psp")!!, "cso"))
        assertEquals(39, RaConsoles.forGame(GameSystems.byId("saturn")!!, "ccd"))
        assertEquals(40, RaConsoles.forGame(GameSystems.byId("dreamcast")!!, "gdi"))
        assertEquals(16, RaConsoles.forGame(GameSystems.byId("gamecube")!!, "gcm"))
        assertEquals(19, RaConsoles.forGame(GameSystems.byId("wii")!!, "wbfs"))
    }

    @Test fun awardValidationAndWatchableAchievements() {
        // rcheevos: md5 of achievement id + user + hardcore flag.
        assertEquals(RetroAchievementsClient.md5("12345user1"), RetroAchievementsClient.md5("12345" + "user" + "1"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", RetroAchievementsClient.md5(""))
        val game = RaGame(1, "Game", 7, listOf(
            RaAchievement(1, "0xH0010=1", "Start", points = 5),
            RaAchievement(2, "", "No condition"),
            RaAchievement(3, "0xH0010=2", "Unofficial", flags = 5),
            RaAchievement(4, "0xH0010=3", "Unknown Emulator"),
        ))
        assertEquals(listOf(1L), RetroAchievementsClient.watchable(game).map { it.id })
    }

    @Test fun chtFilesAreParsed() {
        val cht = """
            cheats = 3

            cheat0_desc = "Infinite Lives"
            cheat0_code = "SXIOPO"
            cheat0_enable = false

            cheat1_desc = ""
            cheat1_code = "AAAA+BBBB"
            cheat1_enable = true

            cheat2_desc = "Empty"
            cheat2_code = ""
        """.trimIndent()
        assertEquals(
            listOf(GameCheat("Infinite Lives", "SXIOPO", false), GameCheat("AAAA+BBBB", "AAAA+BBBB", true)),
            GameCheatStore.parseCht(cht),
        )
        assertEquals(emptyList<GameCheat>(), GameCheatStore.parseCht("nothing here"))
        assertEquals(listOf("Sega - Game Gear"), GameCheatStore.databaseFolders(GameSystems.byId("megadrive")!!, "gg"))
        // Only systems with configured libretro-database names have automatic cheat lookup.
        listOf("nes", "snes", "gb", "gba", "megadrive", "pce", "ngp", "wswan", "lynx",
            "a2600", "vb", "n64", "psx").forEach { id ->
            val system = GameSystems.byId(id)!!
            assert(GameCheatStore.databaseFolders(system, system.extensions.first()).isNotEmpty())
        }
        assertEquals(listOf("Commodore - 64"), GameCheatStore.databaseFolders(GameSystems.byId("c64")!!, "d64"))
        assertEquals(listOf("Nintendo - Nintendo DS"), GameCheatStore.databaseFolders(GameSystems.byId("nds")!!, "nds"))
        assertEquals(emptyList<String>(), GameCheatStore.databaseFolders(GameSystems.byId("wiiu")!!, "wua"))
    }

    @Test fun netplayIdentityDependsOnContentAndCore() {
        val a = folder.newFile("a.sfc").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val b = folder.newFile("b.sfc").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val c = folder.newFile("c.sfc").apply { writeBytes(ByteArray(4096) { (it + 1).toByte() }) }
        assertEquals(GameNetplay.identity(a, "core1", "A").rom, GameNetplay.identity(b, "core1", "B").rom)
        assertNotEquals(GameNetplay.identity(a, "core1", "A").rom, GameNetplay.identity(c, "core1", "A").rom)
        assertEquals("core2", GameNetplay.identity(a, "core2", "A").core)
    }

    @Test fun netplayHashChecksMiddleOfLargeRomAndCoreConfiguration() {
        val game = folder.newFile("large-disc.chd")
        java.io.RandomAccessFile(game, "rw").use { it.setLength(24L * 1024 * 1024) }
        val before = GameNetplay.identity(game, "build-1", "Disc").rom
        // Older range-only hashing missed bytes after 16 MiB but before the last 1 MiB.
        java.io.RandomAccessFile(game, "rw").use {
            it.seek(18L * 1024 * 1024)
            it.writeByte(0x5a)
        }
        val changed = GameNetplay.identity(game, "build-1", "Disc").rom
        assertNotEquals(before, changed)
        assertEquals(64, changed.length) // SHA-256, not SHA-1

        val bios = folder.newFile("bios.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val a = GameNetplay.configurationStamp("build-1", false, mapOf("region" to "pal"), listOf(bios))
        assertEquals(a, GameNetplay.configurationStamp("build-1", false, mapOf("region" to "pal"), listOf(bios)))
        assertNotEquals(a, GameNetplay.configurationStamp("build-1", true, mapOf("region" to "pal"), listOf(bios)))
        assertNotEquals(a, GameNetplay.configurationStamp("build-1", false, mapOf("region" to "ntsc"), listOf(bios)))
        bios.writeBytes(byteArrayOf(1, 2, 4))
        assertNotEquals(a, GameNetplay.configurationStamp("build-1", false, mapOf("region" to "pal"), listOf(bios)))
    }

    @Test fun settingsForPlayOptions() {
        val psx = GameSystems.byId("psx")!!.core
        var state = GameSettingsState()
        assert(state.achievements && !state.hardcore && state.rumble)
        assertNull(state.controllerType(psx, 0))
        state = state.withHardcore(true).withRumble(false).withControllerType(psx, 0, 517)
        assert(state.hardcore && !state.rumble)
        assertEquals(517, state.controllerType(psx, 0))
        assertNull(state.withControllerType(psx, 0, null).controllerType(psx, 0))
    }
}

class GameCoreChannelTest {
    @Test fun coreSourceDefaultsInheritAndOverrideIndependently() {
        val system = GameSystems.byId("snes")!!
        val other = GameSystems.byId("nes")!!
        val global = GameSettingsState().withCoreSource(GameCoreSource.OPTIMIZED)
        assertEquals(GameCoreSource.OPTIMIZED, global.gameCoreSource(system, "game"))
        val scoped = global.withSystemCoreSource(system, GameCoreSource.OPTIMIZED_A57)
        assertEquals(GameCoreSource.OPTIMIZED_A57, scoped.gameCoreSource(system, "game"))
        assertEquals(GameCoreSource.OPTIMIZED, scoped.systemCoreSource(other))
        val explicit = scoped.withGameCoreSource("game", GameCoreSource.AMBIENT)
        assertEquals(GameCoreSource.AMBIENT, explicit.gameCoreSource(system, "game"))
        assertEquals(GameCoreSource.OPTIMIZED_A57, explicit.gameCoreSource(system, "other"))
        assertEquals(GameCoreSource.AUTO, explicit.withGameCoreSource("game", GameCoreSource.AUTO).gameCoreSource(system, "game"))
        assertEquals(GameCoreSource.OPTIMIZED_A57, explicit.withGameCoreSource("game", null).gameCoreSource(system, "game"))
        assertEquals(GameCoreSource.OPTIMIZED, scoped.withSystemCoreSource(system, null).systemCoreSource(system))
        assertEquals(GameCoreSource.OPTIMIZED_A57, scoped.withGameCoreSource("game", GameCoreSource.HOTPATHS).gameCoreSource(system, "game"))
        assertEquals(explicit, GameSettingsState(explicit.values).migrateCoreSource())
    }

    @Test fun optimizedPreferencesRespectAbiAndStaySigned() {
        val core = GameCore("fixture", "Fixture", "MIT")
        assertEquals(listOf(GameCoreSource.OPTIMIZED_A57, GameCoreSource.OPTIMIZED, GameCoreSource.AMBIENT),
            GameCoreSource.OPTIMIZED_A57.preferredSources(core, "arm64-v8a"))
        assertEquals(listOf(GameCoreSource.OPTIMIZED, GameCoreSource.AMBIENT),
            GameCoreSource.OPTIMIZED_A57.preferredSources(core, "armeabi-v7a"))
        assertEquals(listOf(GameCoreSource.OPTIMIZED, GameCoreSource.AMBIENT),
            GameCoreSource.OPTIMIZED.preferredSources(core, "x86_64"))
        assertEquals(listOf(GameCoreSource.AMBIENT), GameCoreSource.AMBIENT.preferredSources(core, "arm64-v8a"))
    }

    @Test fun preferenceResolutionFallsBackOnlyOnAbsence() = kotlinx.coroutines.runBlocking {
        val candidates = listOf(GameCoreSource.OPTIMIZED_A57, GameCoreSource.OPTIMIZED, GameCoreSource.AMBIENT)
        val seen = mutableListOf<GameCoreSource>()
        assertEquals(GameCoreSource.OPTIMIZED, firstAvailableCoreSource(candidates) {
            seen += it
            it == GameCoreSource.OPTIMIZED
        })
        assertEquals(candidates.take(2), seen)
        assertEquals(GameCoreSource.AMBIENT, firstAvailableCoreSource(candidates) { it == GameCoreSource.AMBIENT })
        assertEquals(null, firstAvailableCoreSource(candidates) { false })
        seen.clear()
        val failure = runCatching {
            firstAvailableCoreSource(candidates) {
                seen += it
                throw GameCoreSignatureException("invalid signature")
            }
        }.exceptionOrNull()
        org.junit.Assert.assertTrue(failure is GameCoreSignatureException)
        assertEquals(candidates.take(1), seen)
    }

    @Test fun experimentalReleaseCannotEnterStableChannel() {
        val tag = "core-experiments-core-hotpaths--20261126-1-1"
        org.junit.Assert.assertTrue(GameCoreChannel.matchesChannel(tag, true, "core-hotpaths"))
        org.junit.Assert.assertFalse(GameCoreChannel.matchesChannel(tag, true, "baseline"))
        org.junit.Assert.assertFalse(GameCoreChannel.matchesChannel(tag, false, "core-hotpaths"))
        org.junit.Assert.assertFalse(GameCoreChannel.matchesChannel("cores-1", false, "core-hotpaths"))
        val manifest = GameCoreManifest(tag = tag, profile = "core-hotpaths")
        org.junit.Assert.assertTrue(GameCoreChannel.matchesManifest(manifest, tag, "core-hotpaths"))
        org.junit.Assert.assertFalse(GameCoreChannel.matchesManifest(manifest, tag, "baseline"))
        org.junit.Assert.assertFalse(GameCoreChannel.matchesManifest(manifest, "other", "core-hotpaths"))
        assertEquals(GameCoreSource.AUTO, GameSettingsState().withCoreSource(GameCoreSource.HOTPATHS).coreSource)
        assertEquals(listOf(GameCoreSource.OPTIMIZED, GameCoreSource.OPTIMIZED_A57), GameCoreSource.publicExperiments)
        org.junit.Assert.assertFalse(GameCoreChannel.matchesChannel(tag, true, "optimized"))
        org.junit.Assert.assertTrue(GameCoreChannel.matchesChannel("core-experiments-optimized--20261127-1-1", true, "optimized"))
    }

    @Test fun aLegacyGlobalTestProfileMigratesToAutomaticAndKeepsTestAccess() {
        val legacy = GameSettingsState(mapOf("core_source" to GameCoreSource.HOTPATHS.key, "rumble" to "0"))
        assertEquals(GameCoreSource.AUTO, legacy.coreSource)
        val migrated = legacy.migrateCoreSource()
        assertEquals(GameCoreSource.AUTO, migrated.coreSource)
        org.junit.Assert.assertTrue(migrated.coreExperimentsEnabled)
        assertNull(migrated.values["core_source"])
        assertEquals("0", migrated.values["rumble"])
        assertEquals(migrated, migrated.migrateCoreSource())
        org.junit.Assert.assertFalse(GameSettingsState().coreExperimentsEnabled)
    }

    @Test fun specializedTestBuildsFollowCoreAndAbi() {
        val picoDrive = GameSystems.byId("sega32x")!!.core
        val snes = GameSystems.byId("snes")!!
        val threaded = snes.core("snes9x2010")!!
        assertEquals(listOf(GameCoreSource.AUTO, GameCoreSource.AMBIENT, GameCoreSource.BUILDBOT, GameCoreSource.OPTIMIZED, GameCoreSource.OPTIMIZED_A57), GameCoreSource.regular)
        for (source in GameCoreSource.publicExperiments) {
            val settings = GameSettingsState().withCoreSource(source)
            assertEquals(source, settings.coreSource)
            assertEquals(settings, settings.migrateCoreSource())
        }
        org.junit.Assert.assertTrue(GameCoreSource.PICODRIVE_SH2.supportsExperiment(picoDrive, "arm64-v8a"))
        org.junit.Assert.assertFalse(GameCoreSource.PICODRIVE_SH2.supportsExperiment(picoDrive, "armeabi-v7a"))
        org.junit.Assert.assertFalse(GameCoreSource.PICODRIVE_SH2.supportsExperiment(snes.core, "arm64-v8a"))
        org.junit.Assert.assertTrue(GameCoreSource.SNES_THREADED.supportsExperiment(threaded, "arm64-v8a"))
        org.junit.Assert.assertFalse(GameCoreSource.SNES_THREADED.supportsExperiment(snes.core, "arm64-v8a"))
        org.junit.Assert.assertTrue(GameCoreSource.OPTIMIZED.supportsExperiment(threaded, "arm64-v8a"))
        org.junit.Assert.assertTrue(GameCoreSource.OPTIMIZED_A57.supportsExperiment(threaded, "arm64-v8a"))
        org.junit.Assert.assertFalse(GameCoreSource.OPTIMIZED_A57.supportsExperiment(threaded, "x86"))
        val experiment = GameCoreExperiment(threaded.id, GameCoreSource.OPTIMIZED)
        assertEquals(experiment, GameCoreExperiment.fromKey(experiment.key))
        assertNull(GameCoreExperiment.fromKey("snes9x2010:ambient"))
    }

    @Test fun signedManifestsVerifyAndTamperedOnesDoNot() {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val manifest = """{"version":1,"tag":"cores-1","cores":[]}""".toByteArray()
        val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(manifest)
            sign()
        }
        val publicKey = java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
        org.junit.Assert.assertTrue(GameCoreChannel.verify(manifest, signature, publicKey))
        org.junit.Assert.assertFalse(GameCoreChannel.verify(manifest + ' '.code.toByte(), signature, publicKey))
        org.junit.Assert.assertFalse(GameCoreChannel.verify(manifest, signature, ""))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", GameCoreChannel.sha256(ByteArray(0)))
        assertEquals(GameCoreSource.AUTO, GameCoreSource.fromKey(null))
        assertEquals(GameCoreSource.BUILDBOT, GameSettingsState().withCoreSource(GameCoreSource.BUILDBOT).coreSource)
    }
}
