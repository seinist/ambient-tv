package com.ambient.tv.games

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    /** Hardware rendering does not imply that the libretro core implements a Vulkan context. */
    val supportsVulkan: Boolean = hardware,
    /** Android has no usable OpenGL fallback for this core. */
    val requiresVulkan: Boolean = false,
    /** Some emulators (notably Cemu) do not serialize their entire machine state. */
    val saveStates: Boolean = true,
    val rewind: Boolean = true,
    val netplay: Boolean = true,
    val cheats: Boolean = true,
    val achievements: Boolean = true,
    /** Empty means all Android ABIs are candidates; other values restrict downloads. */
    val supportedAbis: Set<String> = emptySet(),
    /** Verified libretro core-info need_fullpath; candidate for seekable VFS ROM loading. */
    val directFile: Boolean = false,
    /** Backend compatibility options applied after saved user options, including software-only cores. */
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
    /** Touchscreen gesture reaches the core as a Libretro pointer, not a menu tap. */
    val pointerInput: Boolean = false,
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

        // Extended systems: signed builds are offered after their catalog recipes pass release validation.
        GameSystem("nds", "Nintendo DS / DSi", setOf("nds", "dsi", "ids"),
            // The libretro build server has no 32-bit x86 build of melonDS DS.
            GameCore("melondsds", "melonDS DS", "GPL-3.0",
                // melonDS DS 1.4.0 requests desktop OpenGL 3.2 / 4.3, not OpenGL ES.
                // Override old per-core/per-game renderer choices before the core starts.
                glOptions = mapOf("melonds_render_mode" to "software"),
                supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")),
            bios = listOf(
                GameBios("bios7.bin", "df692a80a5b1bc90728bc3dfc76cd948", "Nintendo DS ARM7"),
                GameBios("bios9.bin", "a392174eb3e572fed6447e956bde4b25", "Nintendo DS ARM9"),
            ),
            alternatives = listOf(GameCore("noods", "NooDS", "GPL-3.0", needsBios = true)),
            shortName = "Nintendo DS", pointerInput = true),
        GameSystem("n3ds", "Nintendo 3DS", setOf("3ds", "3dsx", "cci", "cxi"),
            GameCore("citra2018", "Citra 2018", "GPL-2.0", hardware = true, supportsVulkan = false,
                supportedAbis = setOf("arm64-v8a", "x86_64")),
            // Panda3DS is built for arm64 only (no x86_64 build on the libretro build server).
            alternatives = listOf(GameCore("panda3ds", "Panda3DS", "GPL-3.0",
                hardware = true, supportsVulkan = false, rewind = false, netplay = false, achievements = false,
                supportedAbis = setOf("arm64-v8a"))),
            shortName = "3DS", pointerInput = true),
        GameSystem("gamecube", "Nintendo GameCube", setOf("gcm", "dol", "tgc"),
            GameCore("dolphin", "Dolphin", "GPL-2.0", hardware = true, netplay = false, directFile = true,
                supportedAbis = setOf("arm64-v8a", "x86_64")),
            shortName = "GameCube"),
        GameSystem("wii", "Nintendo Wii", setOf("wbfs", "ciso", "wad"),
            GameCore("dolphin", "Dolphin", "GPL-2.0", hardware = true, netplay = false, directFile = true,
                supportedAbis = setOf("arm64-v8a", "x86_64")),
            shortName = "Wii", pointerInput = true),
        GameSystem("wiiu", "Nintendo Wii U", setOf("wua", "wud", "wux", "rpx"),
            GameCore("cemu", "Cemu (experimental)", "MPL-2.0", hardware = true, directFile = true,
                requiresVulkan = true, saveStates = false, rewind = false, netplay = false, cheats = false,
                achievements = false, supportedAbis = setOf("arm64-v8a", "x86_64"),
                vulkanOptions = mapOf("cemu_gpu_api" to "Vulkan")),
            shortName = "Wii U", pointerInput = true),
        GameSystem("psp", "PlayStation Portable", setOf("cso", "zso"),
            GameCore("ppsspp", "PPSSPP", "GPL-2.0", hardware = true, rewind = false, netplay = false, cheats = false, directFile = true,
                vulkanOptions = mapOf("ppsspp_rendering_mode" to "Vulkan"),
                glOptions = mapOf("ppsspp_rendering_mode" to "OpenGL")),
            shortName = "PSP"),
        GameSystem("dreamcast", "Sega Dreamcast", setOf("gdi", "cdi"),
            GameCore("flycast", "Flycast", "GPL-2.0", hardware = true, netplay = false, rewind = false, directFile = true),
            shortName = "Dreamcast"),
        // Disc images shared by multiple systems (.iso/.cue/.chd/.m3u) are selected
        // using their verified disc header or the parent folder, never a global guess.
        GameSystem("saturn", "Sega Saturn", setOf("ccd", "mds"),
            GameCore("mednafen_saturn", "Beetle Saturn", "GPL-2.0", netplay = false),
            bios = listOf(
                GameBios("saturn_bios.bin", "af5828fdff51384f99b3c4926be27762", "Sega Saturn"),
                GameBios("sega_101.bin", "85ec9ca47d8f6807718151cbcca8b964", "Sega Saturn JP"),
                GameBios("mpr-17933.bin", "3240872c70984b6cbfda1586cab68dbe", "Sega Saturn US/EU"),
            ),
            alternatives = listOf(
                GameCore("yabasanshiro", "Yaba Sanshiro (legacy)", "GPL-2.0", hardware = true,
                    supportsVulkan = false, rewind = false, netplay = false, achievements = false),
                GameCore("yabause", "Yabause", "GPL-2.0", netplay = false, achievements = false),
                // Ymir is 64-bit only on the libretro build server.
                GameCore("ymir", "Ymir (experimental)", "GPL-3.0", netplay = false, achievements = false, needsBios = true,
                    supportedAbis = setOf("arm64-v8a", "x86_64")),
            ),
            shortName = "Saturn"),
        GameSystem("ps2", "PlayStation 2", emptySet(),
            GameCore("armsx2", "ARMSX2 (experimental)", "GPL-3.0", hardware = true, rewind = false, netplay = false, cheats = false, achievements = false, directFile = true,
                supportedAbis = setOf("arm64-v8a")),
            shortName = "PS2"),
        GameSystem("c64", "Commodore 64", setOf(
            "d64", "d6z", "d71", "d7z", "d81", "d82", "g64", "g6z", "g41", "g4z",
            "x64", "x6z", "nib", "nbz", "t64", "tap", "prg", "crt", "p00", "d2m", "d4m"
        ),
            GameCore("vice_x64sc", "VICE x64sc", "GPL-2.0"),
            alternatives = listOf(
                GameCore("vice_x64", "VICE x64 (fast)", "GPL-2.0"),
                GameCore("vice_xscpu64", "VICE SuperCPU64", "GPL-2.0"),
            ),
            shortName = "C64"),
        GameSystem("c128", "Commodore 128", emptySet(),
            GameCore("vice_x128", "VICE x128", "GPL-2.0"), shortName = "C128"),
        GameSystem("vic20", "Commodore VIC-20", emptySet(),
            GameCore("vice_xvic", "VICE xvic", "GPL-2.0"), shortName = "VIC-20"),
        GameSystem("plus4", "Commodore Plus/4", emptySet(),
            GameCore("vice_xplus4", "VICE xplus4", "GPL-2.0"), shortName = "Plus/4"),
        GameSystem("pet", "Commodore PET", emptySet(),
            GameCore("vice_xpet", "VICE xpet", "GPL-2.0"), shortName = "PET"),
        GameSystem("cbm2", "Commodore CBM-II", emptySet(),
            GameCore("vice_xcbm2", "VICE xcbm2", "GPL-2.0"),
            alternatives = listOf(GameCore("vice_xcbm5x0", "VICE xcbm5x0", "GPL-2.0")),
            shortName = "CBM-II"),
        GameSystem("a7800", "Atari 7800", setOf("a78"),
            GameCore("prosystem", "ProSystem", "GPL-2.0"),
            shortName = "Atari 7800"),
        GameSystem("a5200", "Atari 5200", setOf("a52"),
            GameCore("a5200", "a5200", "GPL-2.0"),
            shortName = "Atari 5200"),
        GameSystem("sega32x", "Sega 32X", setOf("32x"),
            GameCore("picodrive", "PicoDrive", "MAME (non-commercial)", freeLicense = false),
            shortName = "32X"),
    )

    /** Display order is independent of registration/default-core order. */
    val alphabetical: List<GameSystem> = all.sortedWith(
        compareBy<GameSystem> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }
    )

    /** Menus and chips display short names, so sort by exactly those labels. */
    fun sortIdsByName(ids: Iterable<String>): List<String> = ids.distinct().filter { byId(it) != null }.sortedWith(
        compareBy<String> { (byId(it)?.shortName ?: it).lowercase(Locale.ROOT) }.thenBy { it }
    )

    /** Archives may hold a game (also a disc with its tracks); the system is decided by the files inside. */
    val archiveExtensions = GameArchives.extensions

    private val commodoreIds = setOf("c64", "c128", "vic20", "plus4", "pet", "cbm2")

    /**
     * Shared suffixes still have a bounded set of compatible systems. A folder assignment
     * resolves that ambiguity; it never makes a disc image playable by a cartridge core.
     * Keep these separate from the unambiguous extension defaults above.
     */
    private val sharedFormats: Map<String, Set<String>> = mapOf(
        "iso" to setOf("psx", "psp", "ps2", "saturn", "gamecube", "wii"),
        "rvz" to setOf("gamecube", "wii"),
        "chd" to setOf("psx", "psp", "ps2", "saturn", "dreamcast"),
        "cue" to setOf("psx", "saturn", "dreamcast"),
        "m3u" to (setOf("psx", "ps2", "saturn", "dreamcast", "gamecube", "wii") + commodoreIds),
        "pbp" to setOf("psx", "psp"),
        "ciso" to setOf("gamecube", "wii", "ps2"),
        "cso" to setOf("psp", "ps2"),
        "zso" to setOf("psp", "ps2"),
    ) + all.first { it.id == "c64" }.extensions.associateWith { commodoreIds }

    // Raw dumps have no universal console identity. Offer them only under an explicit
    // compatible folder assignment; generic Files/auto-detection must not guess from .bin.
    private val assignedOnlyFormats = mapOf(
        "bin" to (setOf("megadrive", "sega32x", "a2600", "a5200", "a7800", "psx", "ps2", "dreamcast") + commodoreIds),
    )

    /** Extensions that belong to several consoles, or need the disc header to identify them. */
    val ambiguousExtensions: Set<String> = sharedFormats.keys

    /** A concrete ROM/descriptor, never an uninspected archive or a firmware/core file. */
    fun acceptsFile(system: GameSystem, name: String): Boolean =
        !isBios(name.substringAfterLast('/').substringAfterLast('\\')) && !isCore(name) &&
            extension(name).let { it in system.extensions || system.id in sharedFormats[it].orEmpty() ||
                system.id in assignedOnlyFormats[it].orEmpty() }

    /**
     * Explicit library assignments are a filter, not a fallback to another console. Shared
     * disc/VICE formats may use that assignment; a Wii/GameCube ISO signature remains final.
     * Archives call this with their selected inner filename after bounded metadata probing.
     */
    fun forAssignedFile(
        name: String,
        systemId: String?,
        detected: GameSystem? = forFile(name),
        systemFromHeader: Boolean = false,
    ): GameSystem? {
        if (systemId == null || systemId == "auto") return detected?.takeIf { acceptsFile(it, name) }
        val assigned = byId(systemId) ?: return null
        if (!acceptsFile(assigned, name)) return null
        if (systemFromHeader && detected?.id != assigned.id) return null
        return assigned
    }

    /**
     * Determine the actual system after the ROM was copied from local storage or NAS.
     * GameCube/Wii ISO headers are authoritative; folders only disambiguate shared
     * extensions when no signature is available. No guessing from "game.iso".
     */
    fun forPreparedFile(file: File, originalPath: String = file.name, systemId: String? = null): GameSystem? {
        val header = if (extension(file.name) == "iso") runCatching {
            RandomAccessFile(file, "r").use { disk ->
                if (disk.length() < 0x20) null else ByteArray(0x20).also(disk::readFully)
            }
        }.getOrNull() else null
        return forAssignedFile(file.name, systemId, forSourceFile(file.name, originalPath, header),
            systemFromHeader = forDiscHeader(file.name, header) != null)
    }

    /** A verified disc signature, kept separate from a folder-name hint. */
    fun forDiscHeader(name: String, header: ByteArray?): GameSystem? {
        if (extension(name) == "iso" && header != null && header.size >= 0x20) {
            val ints = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            when {
                ints.getInt(0x18) == 0x5D1C9EA3 -> return byId("wii")
                ints.getInt(0x1c) == 0xC2339F3DL.toInt() -> return byId("gamecube")
            }
        }
        return null
    }

    /** Resolves ambiguous file types without copying a large source, using at most 32 header bytes. */
    fun forSourceFile(name: String, originalPath: String, header: ByteArray? = null): GameSystem? {
        forDiscHeader(name, header)?.let { return it }
        val ext = extension(name)
        if (ext in ambiguousExtensions) {
            val folders = originalPath.replace('\\', '/').lowercase(Locale.ROOT)
                .substringBeforeLast('/', "").split('/').filter(String::isNotBlank)
            val folderIds = mapOf(
                "c64" to "c64", "commodore 64" to "c64",
                "c128" to "c128", "commodore 128" to "c128",
                "vic20" to "vic20", "vic-20" to "vic20",
                "plus4" to "plus4", "plus-4" to "plus4", "plus 4" to "plus4",
                "pet" to "pet", "commodore pet" to "pet",
                "cbm2" to "cbm2", "cbm-ii" to "cbm2",
                "gamecube" to "gamecube", "game cube" to "gamecube",
                "wii" to "wii", "wiiu" to "wiiu", "wii u" to "wiiu",
                "psp" to "psp", "playstation portable" to "psp",
                "ps2" to "ps2", "playstation 2" to "ps2",
                "saturn" to "saturn", "sega saturn" to "saturn",
                "dreamcast" to "dreamcast", "sega dreamcast" to "dreamcast",
                "psx" to "psx", "ps1" to "psx", "playstation" to "psx",
            )
            folders.asReversed().firstNotNullOfOrNull { folder ->
                folderIds[folder]?.let(::byId)?.takeIf { acceptsFile(it, name) }
            }?.let { return it }
        }
        return forFile(name)
    }

    private val byExtension: Map<String, GameSystem> =
        all.flatMap { system -> system.extensions.map { it to system } }.toMap()

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun forFile(name: String): GameSystem? = byExtension[extension(name)]

    /** Files the browser offers as games (archives included). */
    fun isGame(name: String): Boolean = !isBios(name) && !isCore(name) && extension(name).let { it in byExtension || it in ambiguousExtensions || it in archiveExtensions }

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
