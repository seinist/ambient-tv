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
        GameSystems.all.forEach { system -> system.extensions.forEach { assert(RaConsoles.forGame(system, it) != null) } }
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
        GameSystems.all.forEach { assert(GameCheatStore.databaseFolders(it, it.extensions.first()).isNotEmpty()) }
    }

    @Test fun netplayIdentityDependsOnContentAndCore() {
        val a = folder.newFile("a.sfc").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val b = folder.newFile("b.sfc").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val c = folder.newFile("c.sfc").apply { writeBytes(ByteArray(4096) { (it + 1).toByte() }) }
        assertEquals(GameNetplay.identity(a, "core1", "A").rom, GameNetplay.identity(b, "core1", "B").rom)
        assertNotEquals(GameNetplay.identity(a, "core1", "A").rom, GameNetplay.identity(c, "core1", "A").rom)
        assertEquals("core2", GameNetplay.identity(a, "core2", "A").core)
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
