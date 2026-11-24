package com.ambient.tv.romm

import com.ambient.tv.games.GameSystem
import com.ambient.tv.games.GameSystems
import java.util.Locale

/**
 * RomM platform slugs (its own universal slugs since 4.x, IGDB slugs before) to the consoles
 * Ambient TV plays. A platform without an entry is not offered: no core could start it.
 */
object RommPlatforms {
    private val bySlug: Map<String, String> = buildMap {
        fun map(system: String, vararg slugs: String) = slugs.forEach { put(it, system) }
        map("nes", "nes", "famicom", "fds", "nes-classic", "nintendo-entertainment-system")
        map("snes", "snes", "sfam", "super-famicom", "satellaview", "super-nintendo")
        map("gb", "gb", "gbc", "game-boy", "game-boy-color")
        map("gba", "gba", "game-boy-advance")
        map("megadrive", "genesis", "genesis-slash-megadrive", "megadrive", "mega-drive", "sms",
            "master-system", "sega-master-system", "gamegear", "game-gear", "sega-game-gear", "sg1000", "sg-1000")
        map("sega32x", "sega32", "sega32x", "sega-32x")
        map("pce", "tg16", "turbografx16--1", "turbografx-16", "pc-engine", "pce", "supergrafx", "pc-engine-supergrafx")
        map("ngp", "neo-geo-pocket", "neo-geo-pocket-color", "ngp", "ngpc")
        map("wswan", "wonderswan", "wonderswan-color", "ws", "wsc")
        map("lynx", "lynx", "atari-lynx")
        map("a2600", "atari2600", "atari-2600")
        map("a5200", "atari5200", "atari-5200")
        map("a7800", "atari7800", "atari-7800")
        map("vb", "virtualboy", "virtual-boy")
        map("n64", "n64", "nintendo-64")
        map("psx", "psx", "ps", "ps1", "playstation")
        map("ps2", "ps2", "playstation-2")
        map("psp", "psp", "psp-minis", "playstation-portable")
        map("nds", "nds", "nintendo-ds", "nintendo-dsi", "dsi")
        map("n3ds", "3ds", "n3ds", "new-nintendo-3ds", "nintendo-3ds")
        map("gamecube", "ngc", "gamecube", "nintendo-gamecube")
        map("wii", "wii")
        map("wiiu", "wiiu", "wii-u")
        map("dreamcast", "dc", "dreamcast", "sega-dreamcast")
        map("saturn", "saturn", "sega-saturn")
        map("c64", "c64", "commodore-64")
        map("c128", "c128")
        map("vic20", "vic-20", "vic20")
        map("plus4", "c-plus-4", "plus4", "commodore-plus-slash-4")
        map("pet", "cpet", "pet", "commodore-pet")
    }

    /** The Ambient TV system id for a RomM platform, or null when no core plays it. */
    fun systemId(slug: String?, fsSlug: String? = null): String? =
        listOfNotNull(slug, fsSlug).map { it.trim().lowercase(Locale.ROOT) }
            .firstNotNullOfOrNull { bySlug[it] }
            ?.takeIf { GameSystems.byId(it) != null }

    fun system(platform: RommPlatform): GameSystem? = systemId(platform.slug, platform.fsSlug)?.let(GameSystems::byId)

    fun system(rom: RommRom): GameSystem? = systemId(rom.platformSlug, rom.platformFsSlug)?.let(GameSystems::byId)
}
