package com.ambient.tv.games.metadata

import java.util.Locale
import okhttp3.HttpUrl

/** Explicit provider namespaces. Combined Ambient systems are split by ROM extension. */
internal data class GameMetadataPlatform(val libretro: String?, val screenScraper: Int?, val gamesDbNames: Set<String>)

internal object GameMetadataPlatforms {
    private fun platform(libretro: String?, screenScraper: Int?, vararg names: String) =
        GameMetadataPlatform(libretro, screenScraper, names.map(::normalizedPlatformName).toSet())
    private val gb = platform("Nintendo - Game Boy", 9, "Nintendo Game Boy", "Game Boy")
    private val gbc = platform("Nintendo - Game Boy Color", 10, "Nintendo Game Boy Color", "Game Boy Color")
    private val genesis = platform("Sega - Mega Drive - Genesis", 1, "Sega Genesis", "Sega Mega Drive")
    private val master = platform("Sega - Master System - Mark III", 2, "Sega Master System")
    private val gear = platform("Sega - Game Gear", 21, "Sega Game Gear")
    private val sg1000 = platform("Sega - SG-1000", 109, "Sega SG-1000")
    private val nes = platform("Nintendo - Nintendo Entertainment System", 3, "Nintendo Entertainment System (NES)", "Nintendo Entertainment System", "NES")
    private val fds = platform("Nintendo - Family Computer Disk System", 106, "Nintendo Famicom Disk System", "Famicom Disk System")
    private val pcEngine = platform("NEC - PC Engine - TurboGrafx 16", 31, "TurboGrafx 16", "TurboGrafx-16", "PC Engine")
    private val superGrafx = platform("NEC - PC Engine SuperGrafx", 105, "NEC SuperGrafx", "SuperGrafx")
    private val ngp = platform("SNK - Neo Geo Pocket", 25, "Neo Geo Pocket")
    private val ngpc = platform("SNK - Neo Geo Pocket Color", 82, "Neo Geo Pocket Color")
    private val wonderSwan = platform("Bandai - WonderSwan", 45, "WonderSwan", "Wonder Swan")
    private val wonderSwanColor = platform("Bandai - WonderSwan Color", 46, "WonderSwan Color", "Wonder Swan Color")
    private val nds = platform("Nintendo - Nintendo DS", 15, "Nintendo DS")
    // DSiWare has a separate Libretro thumbnail namespace, not a separate confirmed API ID.
    private val dsi = platform("Nintendo - Nintendo DSi", null, "Nintendo DSi")
    private val systems = mapOf(
        "snes" to platform("Nintendo - Super Nintendo Entertainment System", 4, "Super Nintendo (SNES)", "Super Nintendo Entertainment System", "Super Nintendo"),
        "gba" to platform("Nintendo - Game Boy Advance", 12, "Nintendo Game Boy Advance", "Game Boy Advance"),
        "lynx" to platform("Atari - Lynx", 28, "Atari Lynx"),
        "a2600" to platform("Atari - 2600", 26, "Atari 2600"),
        "a5200" to platform("Atari - 5200", 40, "Atari 5200"),
        "a7800" to platform("Atari - 7800", 41, "Atari 7800"),
        "vb" to platform("Nintendo - Virtual Boy", 11, "Nintendo Virtual Boy", "Virtual Boy"),
        "n64" to platform("Nintendo - Nintendo 64", 14, "Nintendo 64"),
        "psx" to platform("Sony - PlayStation", 57, "Sony Playstation", "PlayStation"),
        "ps2" to platform("Sony - PlayStation 2", 58, "Sony Playstation 2", "PlayStation 2"),
        "nds" to nds,
        "n3ds" to platform("Nintendo - Nintendo 3DS", 17, "Nintendo 3DS"),
        "gamecube" to platform("Nintendo - GameCube", 13, "Nintendo GameCube"),
        "wii" to platform("Nintendo - Wii", 16, "Nintendo Wii", "Wii"),
        "wiiu" to platform("Nintendo - Wii U", 18, "Nintendo Wii U", "Wii U"),
        "psp" to platform("Sony - PlayStation Portable", 61, "Sony PSP", "Sony PlayStation Portable", "PlayStation Portable"),
        "dreamcast" to platform("Sega - Dreamcast", 23, "Sega Dreamcast"),
        "saturn" to platform("Sega - Saturn", 22, "Sega Saturn"),
        "c64" to platform("Commodore - 64", 66, "Commodore 64"),
        "c128" to platform(null, null, "Commodore 128"),
        "vic20" to platform("Commodore - VIC-20", 73, "Commodore VIC-20", "VIC-20"),
        "plus4" to platform("Commodore - Plus-4", 99, "Commodore Plus/4", "Commodore Plus 4"),
        "pet" to platform("Commodore - PET", 240, "Commodore PET"),
        "cbm2" to platform(null, null, "Commodore CBM-II"),
        "sega32x" to platform("Sega - 32X", 19, "Sega 32X"),
    )

    /** The official thumbnail repos use the same namespaces with spaces replaced by underscores. */
    val libretroThumbnailRepositories: Map<String, String> = (systems.values + listOf(
        gb, gbc, genesis, master, gear, sg1000, nes, fds, pcEngine, superGrafx, ngp, ngpc, wonderSwan, wonderSwanColor, dsi,
    )).mapNotNull { it.libretro }.distinct().associateWith { it.replace(' ', '_') }

    fun forQuery(query: GameMetadataQuery): List<GameMetadataPlatform> {
        val extension = query.filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (query.systemId) {
            "nds" -> if (extension == "dsi") listOf(dsi, nds) else listOf(nds, dsi)
            "nes" -> when (extension) {
                "fds" -> listOf(fds)
                "nes", "unf", "unif" -> listOf(nes)
                else -> listOf(nes, fds)
            }
            "gb" -> when (extension) { "gbc" -> listOf(gbc); "gb", "dmg" -> listOf(gb); else -> listOf(gb, gbc) }
            "megadrive" -> when (extension) { "sms" -> listOf(master); "gg" -> listOf(gear); "sg" -> listOf(sg1000)
                "md", "gen", "smd", "68k" -> listOf(genesis); else -> listOf(genesis, master, gear, sg1000) }
            "pce" -> when (extension) {
                "sgx" -> listOf(superGrafx)
                "pce" -> listOf(pcEngine)
                else -> listOf(pcEngine, superGrafx)
            }
            "ngp" -> if (extension == "ngc") listOf(ngpc) else if (extension == "ngp") listOf(ngp) else listOf(ngp, ngpc)
            "wswan" -> if (extension == "wsc") listOf(wonderSwanColor) else if (extension == "ws") listOf(wonderSwan) else listOf(wonderSwan, wonderSwanColor)
            else -> listOfNotNull(systems[query.systemId])
        }
    }

    fun supports(query: GameMetadataQuery, provider: String): Boolean = forQuery(query).any { platform ->
        when (provider) {
            GameMetadataProviderIds.LIBRETRO -> platform.libretro != null
            GameMetadataProviderIds.SCREENSCRAPER -> platform.screenScraper != null
            GameMetadataProviderIds.THEGAMESDB -> platform.gamesDbNames.isNotEmpty()
            else -> false
        }
    }

    /** Use another already-configured provider only if the chosen one has no platform mapping. */
    fun automaticProvider(query: GameMetadataQuery, preferred: String, available: List<String>): String =
        if (supports(query, preferred)) preferred
        else available.firstOrNull { supports(query, it) } ?: preferred

    fun libretroCandidates(query: GameMetadataQuery, catalogTitles: List<String> = emptyList()): List<GameMetadataCandidate> {
        val systems = forQuery(query).mapNotNull { it.libretro }
        if (systems.isEmpty()) return emptyList()
        val pinned = query.providerGameId?.split('|', limit = 2)?.takeIf { it.size == 2 && it[0] in systems }
        val catalog = catalogTitles.asSequence().map { it.trim().take(256) }
            .filter(String::isNotBlank).distinct().take(30).toSet()
        val names = if (pinned != null) listOf(pinned[1]) else (catalog + listOfNotNull(
            query.canonicalTitle?.takeIf(String::isNotBlank), query.title.takeIf(String::isNotBlank),
            query.filename.substringAfterLast('/').substringBeforeLast('.').takeIf(String::isNotBlank),
            gameCatalogFilename(query.systemId, query.filename).takeIf(String::isNotBlank),
            gameMetadataSearchName(query.title).takeIf(String::isNotBlank),
            gameMetadataSearchName(gameCatalogFilename(query.systemId, query.filename)).takeIf(String::isNotBlank),
        )).map { it.trim().take(256) }.distinct().take(if (catalog.isEmpty()) 6 else 30)
        return names.flatMap { name -> (pinned?.let { listOf(it[0]) } ?: systems).map { system ->
            val imageName = name.map { if (it in "&*/:`<>?\\|\"" || it.isISOControl()) '_' else it }.joinToString("") + ".png"
            fun image(type: String) = HttpUrl.Builder().scheme("https").host("thumbnails.libretro.com")
                .addPathSegment(system).addPathSegment(type).addPathSegment(imageName).build().toUri().toASCIIString()
            GameMetadataCandidate(GameMetadata(
                title = name, canonicalTitle = name, provider = GameMetadataProviderIds.LIBRETRO,
                providerGameId = system + "|" + name, coverUrl = image("Named_Boxarts"),
                backdropUrl = image("Named_Snaps"), titleScreenUrl = image("Named_Titles"),
                sourceUrl = "https://github.com/libretro-thumbnails/libretro-thumbnails",
                region = query.region, language = query.language,
            ), exactMatch = pinned != null || query.canonicalTitle == name, catalogMatch = name in catalog)
        } }.take(if (catalog.isEmpty()) 12 else 30)
    }
}

internal fun normalizedPlatformName(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
