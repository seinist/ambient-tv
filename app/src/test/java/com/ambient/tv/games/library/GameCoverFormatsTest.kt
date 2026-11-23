package com.ambient.tv.games.library

import com.ambient.tv.games.GameSystems
import org.junit.Assert.*
import org.junit.Test

class GameCoverFormatsTest {
    @Test fun everyRegisteredSystemHasAConsideredFiniteFallback() {
        assertEquals(GameSystems.all.map { it.id }.toSet(), GameCoverFormats.supportedSystemIds)
        GameSystems.all.forEach { system ->
            val ratio = GameCoverFormats.aspectRatio(system.id, "Unidentified.zip")
            assertTrue(system.id, ratio.isFinite() && ratio in 0.4f..1.5f)
        }
        assertEquals(1f, GameCoverFormats.aspectRatio("future-system", "unknown.bin"), 0f)
    }

    @Test fun selectedArtworkWinsOverSystemRegionAndItsUnusualShapeIsPreserved() {
        assertEquals(4f / 3f, GameCoverFormats.aspectRatio("snes", "Game (Japan).sfc", "jp", 400, 300), 0f)
        assertEquals(0.75f, GameCoverFormats.aspectRatio("psx", "Game (USA).chd", "us", 300, 400), 0f)
        assertEquals(8192f, GameCoverFormats.aspectRatio("gb", "Game.gb", width = 8192, height = 1), 0f)
        assertEquals(1f / 8192f, GameCoverFormats.aspectRatio("gb", "Game.gb", width = 1, height = 8192), 0f)
    }

    @Test fun invalidOrIncompleteBoundsCannotPoisonTheLayout() {
        for ((width, height) in listOf(null to 10, 10 to null, 0 to 3, 3 to -1,
            Int.MAX_VALUE to Int.MAX_VALUE, 8192 to 8192, 9000 to 100)) {
            assertEquals(1.4f, GameCoverFormats.aspectRatio("snes", "Game.sfc", width = width, height = height), 0f)
        }
    }

    @Test fun explicitFilenameRegionBeatsProviderPreferenceAndTitlesDoNotInventRegions() {
        assertEquals(0.55f, GameCoverFormats.aspectRatio("snes", "Game (Japan).sfc", "eu"), 0f)
        assertEquals(1.4f, GameCoverFormats.aspectRatio("snes", "Game [Europe].sfc", "jp"), 0f)
        assertEquals(1.4f, GameCoverFormats.aspectRatio("snes", "Japan Pro Golf.sfc"), 0f)
        assertEquals(1.4f, GameCoverFormats.aspectRatio("snes", "Game (USA, Japan).sfc", "jp"), 0f)
        assertEquals(0.55f, GameCoverFormats.aspectRatio("snes", "Game.zip", "jpn"), 0f)
        assertEquals(1f, GameCoverFormats.aspectRatio("saturn", "Game (Japan).chd"), 0f)
        assertTrue(GameCoverFormats.aspectRatio("saturn", "Game (Europe).chd") < 1f)
    }

    @Test fun combinedSystemsResolveEveryRegisteredSubplatformFromRomIdentity() {
        val cases = listOf(
            Triple("nes", "Game.fds", "fds"), Triple("nes", "Game.nes", "nes"),
            Triple("gb", "Game.gb", "gb"), Triple("gb", "Game.gbc", "gbc"),
            Triple("gb", "Game.dmg", "gb"), Triple("megadrive", "Game.md", "megadrive"),
            Triple("megadrive", "Game.sms", "mastersystem"), Triple("megadrive", "Game.gg", "gamegear"),
            Triple("megadrive", "Game.sg", "sg1000"), Triple("pce", "Game.pce", "pce"),
            Triple("pce", "Game.sgx", "supergrafx"), Triple("ngp", "Game.ngp", "ngp"),
            Triple("ngp", "Game.ngc", "ngpc"), Triple("wswan", "Game.ws", "wswan"),
            Triple("wswan", "Game.wsc", "wswancolor"), Triple("nds", "Game.nds", "nds"),
            Triple("nds", "Game.dsi", "dsi"), Triple("nds", "Game.ids", "dsi"),
        )
        cases.forEach { (system, file, platform) ->
            assertEquals(file, platform, GameCoverFormats.platformId(system, file))
            assertEquals(file, platform, GameCoverFormats.platformId(system, file.uppercase() + ".ZIP"))
        }
    }

    @Test fun matchedPlatformDisambiguatesArchivesWithoutChangingKnownRomIdentity() {
        val cases = listOf(
            Triple("nes", "Nintendo - Family Computer Disk System", "fds"),
            Triple("gb", "Nintendo - Game Boy Color", "gbc"),
            Triple("megadrive", "Sega - Game Gear", "gamegear"),
            Triple("megadrive", "Sega - Master System - Mark III", "mastersystem"),
            Triple("megadrive", "Sega - SG-1000", "sg1000"),
            Triple("pce", "NEC - PC Engine SuperGrafx", "supergrafx"),
            Triple("ngp", "SNK - Neo Geo Pocket Color", "ngpc"),
            Triple("wswan", "Bandai - WonderSwan Color", "wswancolor"),
            Triple("nds", "Nintendo - Nintendo DSi", "dsi"),
        )
        cases.forEach { (system, namespace, platform) ->
            assertEquals(platform, GameCoverFormats.platformId(system, "Game.zip", namespace))
        }
        assertEquals("gb", GameCoverFormats.platformId("gb", "Game.gb", "Nintendo - Game Boy Color"))
        assertEquals("snes", GameCoverFormats.platformId("snes", "Game.zip", "Sony - PlayStation"))
        assertEquals("gb", GameCoverFormats.platformId("gb", "Game.zip", "mGBA"))
        assertEquals("c64", GameCoverFormats.platformId("c64", "Game.d64", "VICE x128"))
    }

    @Test fun computerPackagesRemainNeutralUntilActualArtworkIsKnown() {
        for (system in listOf("c64", "c128", "vic20", "plus4", "pet", "cbm2")) {
            assertEquals(1f, GameCoverFormats.aspectRatio(system, "Game (Japan).tap"), 0f)
            assertEquals(0.5f, GameCoverFormats.aspectRatio(system, "Game.prg", width = 100, height = 200), 0f)
        }
    }
}
