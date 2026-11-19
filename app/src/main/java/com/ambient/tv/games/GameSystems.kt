package com.ambient.tv.games

import java.util.Locale

/**
 * A libretro core as published by the libretro buildbot (`<id>_libretro_android.so`).
 * [freeLicense]: GPL-compatible; false = non-commercial use only. Ambient TV never bundles a core:
 * the user loads it on first use (see GameCoreManager).
 */
data class GameCore(
    val id: String,
    val name: String,
    val license: String,
    val freeLicense: Boolean = true,
    /** Renders through the GPU itself (OpenGL ES or Vulkan) instead of handing over finished frames. */
    val hardware: Boolean = false,
    /** Core options that make the core render with Vulkan, and with OpenGL ES (GPU cores). */
    val vulkanOptions: Map<String, String> = emptyMap(),
    val glOptions: Map<String, String> = emptyMap(),
    /** Runs only with one of its system's firmware files (no built-in replacement). */
    val needsBios: Boolean = false,
)

/**
 * A firmware file a core reads from the system directory. [requiredFor]: null = optional (the core
 * has a replacement or runs without), empty = always required, else required for these extensions.
 */
data class GameBios(val file: String, val md5: String, val description: String, val requiredFor: Set<String>? = null) {
    fun requiredFor(extension: String): Boolean = requiredFor != null && (requiredFor.isEmpty() || extension in requiredFor)
}

/**
 * A console or handheld: its file extensions, the core that plays it by default, further cores
 * that can ([alternatives], chosen per system or per game) and the firmware it may use.
 */
data class GameSystem(
    val id: String,
    val name: String,
    val extensions: Set<String>,
    val core: GameCore,
    val bios: List<GameBios> = emptyList(),
    val alternatives: List<GameCore> = emptyList(),
    /** Short name for lists (file browser). */
    val shortName: String = name,
) {
    /** Every core for this system, the default first. */
    val cores: List<GameCore> get() = listOf(core) + alternatives

    fun core(id: String?): GameCore? = cores.firstOrNull { it.id == id }
}

private val MGBA = GameCore("mgba", "mGBA", "MPL-2.0")

object GameSystems {
    val all: List<GameSystem> = listOf(
        GameSystem("nes", "Nintendo Entertainment System", setOf("nes", "fds", "unf", "unif"),
            GameCore("nestopia", "Nestopia UE", "GPL-2.0"),
            listOf(GameBios("disksys.rom", "ca30b50f880eb660a320674ed365ef7a", "Famicom Disk System", setOf("fds"))),
            alternatives = listOf(GameCore("fceumm", "FCEUmm", "GPL-2.0"), GameCore("mesen", "Mesen", "GPL-3.0")),
            shortName = "NES"),
        GameSystem("snes", "Super Nintendo", setOf("sfc", "smc", "swc", "fig", "bs"),
            GameCore("snes9x", "Snes9x", "Snes9x (non-commercial)", freeLicense = false),
            alternatives = listOf(
                GameCore("snes9x2010", "Snes9x 2010", "Snes9x (non-commercial)", freeLicense = false),
                GameCore("bsnes", "bsnes", "GPL-3.0"),
            ),
            shortName = "SNES"),
        GameSystem("gb", "Game Boy / Game Boy Color", setOf("gb", "gbc", "dmg"),
            GameCore("gambatte", "Gambatte", "GPL-2.0"),
            listOf(
                GameBios("gb_bios.bin", "32fbbd84168d3482956eb3c5051637f5", "Game Boy"),
                GameBios("gbc_bios.bin", "dbfce9db9deaa2567f6a84fde55f9680", "Game Boy Color"),
            ),
            alternatives = listOf(GameCore("sameboy", "SameBoy", "MIT"), MGBA),
            shortName = "Game Boy"),
        GameSystem("gba", "Game Boy Advance", setOf("gba"),
            MGBA,
            listOf(GameBios("gba_bios.bin", "a860e8c0b6d573d191e4ec7db1b1e4f6", "Game Boy Advance")),
            alternatives = listOf(GameCore("gpsp", "gpSP", "GPL-2.0")),
            shortName = "GBA"),
        GameSystem("megadrive", "Sega Mega Drive / Master System / Game Gear", setOf("md", "gen", "smd", "68k", "sms", "gg", "sg"),
            GameCore("genesis_plus_gx", "Genesis Plus GX", "Genesis Plus GX (non-commercial)", freeLicense = false),
            listOf(
                GameBios("bios_MD.bin", "45e298905a08f9cfb38fd504cd6dbc84", "Mega Drive (TMSS)"),
                GameBios("bios_E.sms", "840481177270d5642a14ca71ee72844c", "Master System EU"),
                GameBios("bios_U.sms", "840481177270d5642a14ca71ee72844c", "Master System US"),
                GameBios("bios_J.sms", "24a519c53f67b00640d0048ef7089105", "Master System JP"),
                GameBios("bios.gg", "672e104c3be3a238301aceffc3b23fd6", "Game Gear"),
            ),
            alternatives = listOf(GameCore("picodrive", "PicoDrive", "MAME (non-commercial)", freeLicense = false)),
            shortName = "Mega Drive"),
        GameSystem("pce", "PC Engine / TurboGrafx-16", setOf("pce", "sgx"),
            GameCore("mednafen_pce_fast", "Beetle PCE Fast", "GPL-2.0"),
            alternatives = listOf(GameCore("mednafen_supergrafx", "Beetle SuperGrafx", "GPL-2.0")),
            shortName = "PC Engine"),
        GameSystem("ngp", "Neo Geo Pocket", setOf("ngp", "ngc"),
            GameCore("mednafen_ngp", "Beetle NeoPop", "GPL-2.0"),
            alternatives = listOf(GameCore("race", "RACE", "GPL-2.0")),
            shortName = "Neo Geo Pocket"),
        GameSystem("wswan", "WonderSwan", setOf("ws", "wsc"),
            GameCore("mednafen_wswan", "Beetle Cygne", "GPL-2.0"),
            shortName = "WonderSwan"),
        GameSystem("lynx", "Atari Lynx", setOf("lnx"),
            GameCore("handy", "Handy", "Zlib"),
            listOf(GameBios("lynxboot.img", "fcd403db69f54290b51035d82f835e7b", "Lynx", emptySet())),
            alternatives = listOf(GameCore("mednafen_lynx", "Beetle Lynx", "GPL-2.0", needsBios = true)),
            shortName = "Lynx"),
        GameSystem("a2600", "Atari 2600", setOf("a26"),
            GameCore("stella2014", "Stella 2014", "GPL-2.0"),
            shortName = "Atari 2600"),
        GameSystem("vb", "Virtual Boy", setOf("vb", "vboy"),
            GameCore("mednafen_vb", "Beetle VB", "GPL-2.0"),
            shortName = "Virtual Boy"),
        GameSystem("n64", "Nintendo 64", setOf("n64", "z64", "v64"),
            GameCore(
                "mupen64plus_next_gles3", "Mupen64Plus-Next", "GPL-2.0", hardware = true,
                // ParaLLEl-RDP/RSP render with Vulkan (in the ARM builds); GLideN64 with OpenGL ES.
                vulkanOptions = mapOf("mupen64plus-rdp-plugin" to "parallel", "mupen64plus-rsp-plugin" to "parallel"),
                glOptions = mapOf("mupen64plus-rdp-plugin" to "gliden64", "mupen64plus-rsp-plugin" to "hle"),
            ),
            alternatives = listOf(GameCore(
                "parallel_n64", "ParaLLEl N64", "GPL-2.0", hardware = true,
                vulkanOptions = mapOf("parallel-n64-gfxplugin" to "parallel", "parallel-n64-rspplugin" to "parallel"),
                glOptions = mapOf("parallel-n64-gfxplugin" to "glide64", "parallel-n64-rspplugin" to "auto"),
            )),
            shortName = "N64"),
        GameSystem("psx", "PlayStation", setOf("cue", "chd", "pbp", "m3u"),
            GameCore("pcsx_rearmed", "PCSX ReARMed", "GPL-2.0"),
            // Optional: without one the core uses its own BIOS replacement, which runs fewer games.
            listOf(
                GameBios("scph5501.bin", "490f666e1afb15b7362b406ed1cea246", "PlayStation US 3.0"),
                GameBios("scph1001.bin", "924e392ed05558ffdb115408c263dccf", "PlayStation US 2.0"),
                GameBios("scph7001.bin", "1e68c231d0896b7eadcad1d7d8e76129", "PlayStation US 4.1"),
                GameBios("scph101.bin", "6e3735ff4c7dc899ee98981385f6f3d0", "PlayStation US 4.4"),
                GameBios("PSXONPSP660.bin", "c53ca5908936d412331790f4426c6c33", "PlayStation (PSP)"),
            ),
            alternatives = listOf(
                GameCore("swanstation", "SwanStation", "GPL-3.0", needsBios = true),
                GameCore(
                    "mednafen_psx_hw", "Beetle PSX HW", "GPL-2.0", hardware = true, needsBios = true,
                    vulkanOptions = mapOf("beetle_psx_hw_renderer" to "hardware_vk"),
                    glOptions = mapOf("beetle_psx_hw_renderer" to "hardware_gl"),
                ),
            ),
            shortName = "PlayStation"),
    )

    /** Archives may hold a game (also a disc with its tracks); the system is decided by the files inside. */
    val archiveExtensions = GameArchives.extensions

    private val byExtension: Map<String, GameSystem> =
        all.flatMap { system -> system.extensions.map { it to system } }.toMap()

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun forFile(name: String): GameSystem? = byExtension[extension(name)]

    /** Files the browser offers as games (archives included). */
    fun isGame(name: String): Boolean = !isBios(name) && !isCore(name) && extension(name).let { it in byExtension || it in archiveExtensions }

    fun byId(id: String): GameSystem? = all.firstOrNull { it.id == id }

    /** Firmware files by name (case-insensitive), for the file browser and imports. */
    fun bios(name: String): List<Pair<GameSystem, GameBios>> = all.flatMap { system ->
        system.bios.filter { it.file.equals(name, ignoreCase = true) }.map { system to it }
    }

    fun isBios(name: String): Boolean = bios(name).isNotEmpty()

    /** Every core, once. */
    val cores: List<GameCore> get() = all.flatMap { it.cores }.distinctBy { it.id }

    /**
     * The core a libretro core file belongs to: "<core>_libretro_android.so" as the buildbot names
     * it, "<core>_libretro.so" as RetroArch keeps it, each also zipped.
     */
    fun coreForFile(name: String): GameCore? {
        val lower = name.lowercase(Locale.ROOT).removeSuffix(".zip")
        if (!lower.endsWith(".so")) return null
        val stem = lower.removeSuffix(".so").removeSuffix("_android")
        if (!stem.endsWith("_libretro")) return null
        val id = stem.removeSuffix("_libretro")
        return cores.firstOrNull { it.id == id }
    }

    fun isCore(name: String): Boolean = coreForFile(name) != null
}
