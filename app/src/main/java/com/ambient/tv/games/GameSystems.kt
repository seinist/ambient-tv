package com.ambient.tv.games

import java.util.Locale

/**
 * A libretro core as published by the libretro buildbot (`<id>_libretro_android.so`).
 * [freeLicense]: GPL-compatible; false = non-commercial use only. Ambient TV never bundles a core:
 * the user loads it on first use (see GameCoreManager).
 */
data class GameCore(val id: String, val name: String, val license: String, val freeLicense: Boolean = true)

/** A console or handheld: its file extensions and the core that plays it. */
data class GameSystem(
    val id: String,
    val name: String,
    val extensions: Set<String>,
    val core: GameCore,
)

object GameSystems {
    val all: List<GameSystem> = listOf(
        GameSystem("nes", "Nintendo Entertainment System", setOf("nes", "fds", "unf", "unif"),
            GameCore("nestopia", "Nestopia UE", "GPL-2.0")),
        GameSystem("snes", "Super Nintendo", setOf("sfc", "smc", "swc", "fig", "bs"),
            GameCore("snes9x", "Snes9x", "Snes9x (non-commercial)", freeLicense = false)),
        GameSystem("gb", "Game Boy / Game Boy Color", setOf("gb", "gbc", "dmg"),
            GameCore("gambatte", "Gambatte", "GPL-2.0")),
        GameSystem("gba", "Game Boy Advance", setOf("gba"),
            GameCore("mgba", "mGBA", "MPL-2.0")),
        GameSystem("megadrive", "Sega Mega Drive / Master System / Game Gear", setOf("md", "gen", "smd", "68k", "sms", "gg", "sg"),
            GameCore("genesis_plus_gx", "Genesis Plus GX", "Genesis Plus GX (non-commercial)", freeLicense = false)),
        GameSystem("pce", "PC Engine / TurboGrafx-16", setOf("pce", "sgx"),
            GameCore("mednafen_pce_fast", "Beetle PCE Fast", "GPL-2.0")),
        GameSystem("ngp", "Neo Geo Pocket", setOf("ngp", "ngc"),
            GameCore("mednafen_ngp", "Beetle NeoPop", "GPL-2.0")),
        GameSystem("wswan", "WonderSwan", setOf("ws", "wsc"),
            GameCore("mednafen_wswan", "Beetle Cygne", "GPL-2.0")),
        GameSystem("lynx", "Atari Lynx", setOf("lnx"),
            GameCore("handy", "Handy", "Zlib")),
        GameSystem("a2600", "Atari 2600", setOf("a26"),
            GameCore("stella2014", "Stella 2014", "GPL-2.0")),
        GameSystem("vb", "Virtual Boy", setOf("vb", "vboy"),
            GameCore("mednafen_vb", "Beetle VB", "GPL-2.0")),
        GameSystem("n64", "Nintendo 64", setOf("n64", "z64", "v64"),
            GameCore("mupen64plus_next_gles3", "Mupen64Plus-Next", "GPL-2.0")),
        GameSystem("psx", "PlayStation", setOf("cue", "chd", "pbp", "m3u"),
            GameCore("pcsx_rearmed", "PCSX ReARMed", "GPL-2.0")),
    )

    /** Archives may hold one game; the system is decided by the file inside. */
    val archiveExtensions = setOf("zip")

    private val byExtension: Map<String, GameSystem> =
        all.flatMap { system -> system.extensions.map { it to system } }.toMap()

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun forFile(name: String): GameSystem? = byExtension[extension(name)]

    /** Files the browser offers as games (archives included). */
    fun isGame(name: String): Boolean = extension(name).let { it in byExtension || it in archiveExtensions }

    fun byId(id: String): GameSystem? = all.firstOrNull { it.id == id }
}
