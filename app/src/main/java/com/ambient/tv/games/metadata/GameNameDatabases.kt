package com.ambient.tv.games.metadata

/**
 * The libretro-database DATs RetroArch loads with "Update Databases": exact catalog names by
 * ROM checksum, nothing else. Libretro's box art uses the same names, so a game named from them
 * gets its cover automatically. Combined Ambient systems take the DATs of every console they
 * play. Disc systems are left out: the library never hashes a disc image.
 */
internal object GameNameDatabases {
    private const val NO_INTRO = "metadat/no-intro/"
    // Lynx and 7800 dumps usually keep their header; No-Intro lists them without it.
    private const val HEADERED = "metadat/headered/"

    private val files: Map<String, List<String>> = mapOf(
        "nes" to listOf(NO_INTRO + "Nintendo - Nintendo Entertainment System", NO_INTRO + "Nintendo - Family Computer Disk System"),
        "snes" to listOf(NO_INTRO + "Nintendo - Super Nintendo Entertainment System"),
        "gb" to listOf(NO_INTRO + "Nintendo - Game Boy", NO_INTRO + "Nintendo - Game Boy Color"),
        "gba" to listOf(NO_INTRO + "Nintendo - Game Boy Advance"),
        "n64" to listOf(NO_INTRO + "Nintendo - Nintendo 64"),
        "nds" to listOf(NO_INTRO + "Nintendo - Nintendo DS", NO_INTRO + "Nintendo - Nintendo DSi"),
        "n3ds" to listOf(NO_INTRO + "Nintendo - Nintendo 3DS", NO_INTRO + "Nintendo - New Nintendo 3DS"),
        "vb" to listOf(NO_INTRO + "Nintendo - Virtual Boy"),
        "megadrive" to listOf(NO_INTRO + "Sega - Mega Drive - Genesis", NO_INTRO + "Sega - Master System - Mark III",
            NO_INTRO + "Sega - Game Gear", NO_INTRO + "Sega - SG-1000"),
        "sega32x" to listOf(NO_INTRO + "Sega - 32X"),
        "pce" to listOf(NO_INTRO + "NEC - PC Engine - TurboGrafx 16", NO_INTRO + "NEC - PC Engine SuperGrafx"),
        "ngp" to listOf(NO_INTRO + "SNK - Neo Geo Pocket", NO_INTRO + "SNK - Neo Geo Pocket Color"),
        "wswan" to listOf(NO_INTRO + "Bandai - WonderSwan", NO_INTRO + "Bandai - WonderSwan Color"),
        "lynx" to listOf(HEADERED + "Atari - Lynx", NO_INTRO + "Atari - Lynx"),
        "a2600" to listOf(NO_INTRO + "Atari - 2600"),
        "a5200" to listOf(NO_INTRO + "Atari - 5200"),
        "a7800" to listOf(HEADERED + "Atari - 7800", NO_INTRO + "Atari - 7800"),
        "c64" to listOf(NO_INTRO + "Commodore - 64"),
        "vic20" to listOf(NO_INTRO + "Commodore - VIC-20"),
        "plus4" to listOf(NO_INTRO + "Commodore - Plus-4"),
    ).mapValues { (_, names) -> names.map { "$it.dat" } }

    /** Paths below libretro-database's root; empty for a system without one. */
    fun files(systemId: String): List<String> = files[systemId].orEmpty()

    val systems: Set<String> get() = files.keys
}
