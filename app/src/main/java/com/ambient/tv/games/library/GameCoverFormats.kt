package com.ambient.tv.games.library

import com.ambient.tv.games.metadata.GameArtworkFiles
import java.util.Locale

/**
 * Missing-cover frames approximate box families; they are not a claim about every release.
 * Actual selected artwork always supplies its own dimensions. This also covers unusual
 * editions, regional variants and user-created artwork without cropping or stretching.
 *
 * Platform namespaces follow the official libretro thumbnail registry:
 * https://github.com/libretro-thumbnails/libretro-thumbnails
 * Combined Ambient systems are resolved from the ROM suffix or a matched provider namespace,
 * never from the selected emulator (mGBA, VICE and others run several different machines).
 */
object GameCoverFormats {
    private const val PORTRAIT = 2f / 3f
    private const val DISC_CASE = 1f
    private const val CARTRIDGE_LANDSCAPE = 1.4f
    private const val JAPANESE_CARTRIDGE = 0.55f
    private const val DVD_CASE = 0.71f
    private const val PSP_CASE = 0.58f
    private const val HANDHELD_BOX = 1f

    /** Explicit coverage makes adding a playable system without considering its artwork visible. */
    val supportedSystemIds: Set<String> = setOf(
        "nes", "snes", "gb", "gba", "megadrive", "pce", "ngp", "wswan", "lynx",
        "a2600", "vb", "n64", "psx", "nds", "n3ds", "gamecube", "wii", "wiiu",
        "psp", "dreamcast", "saturn", "ps2", "c64", "c128", "vic20", "plus4",
        "pet", "cbm2", "a7800", "a5200", "sega32x",
    )

    private val neutralComputers = setOf("c64", "c128", "vic20", "plus4", "pet", "cbm2")
    private val archiveExtensions = setOf("zip", "7z", "gz", "rar")
    private val regionTags = Regex("""\(([^)]*)\)|\[([^]]*)]""")
    private enum class Region { JAPAN, WESTERN, OTHER }

    /** Positive, finite width / height. Layout bounds belong to the caller, not the image. */
    fun aspectRatio(
        systemId: String,
        filename: String,
        region: String? = null,
        width: Int? = null,
        height: Int? = null,
        platform: String? = null,
    ): Float {
        if (GameArtworkFiles.validDimensions(width, height)) return width!!.toFloat() / height!!
        val location = filenameRegion(filename) ?: metadataRegion(region)
        return when (platformId(systemId, filename, platform)) {
            "snes", "n64" -> if (location == Region.JAPAN) JAPANESE_CARTRIDGE else CARTRIDGE_LANDSCAPE
            "nes" -> when {
                // Famicom packages vary substantially; do not invent one Japanese shape.
                location == Region.JAPAN -> 1f
                else -> PORTRAIT
            }
            "fds" -> 1f
            "gb", "gbc" -> if (location == Region.JAPAN) 0.8f else HANDHELD_BOX
            "gba" -> if (location == Region.JAPAN) CARTRIDGE_LANDSCAPE else HANDHELD_BOX
            "megadrive", "mastersystem", "sg1000" -> PORTRAIT
            "gamegear" -> if (location == Region.JAPAN) 1f else PORTRAIT
            "pce" -> if (location == Region.WESTERN) PORTRAIT else DISC_CASE
            "supergrafx" -> DISC_CASE
            "ngp", "ngpc" -> if (location == Region.JAPAN) 1f else 0.8f
            "wswan", "wswancolor" -> 0.8f
            "lynx", "a2600", "a5200", "a7800", "sega32x" -> PORTRAIT
            "vb" -> if (location == Region.JAPAN) JAPANESE_CARTRIDGE else CARTRIDGE_LANDSCAPE
            "psx", "dreamcast" -> DISC_CASE
            "saturn" -> if (location == Region.JAPAN) DISC_CASE else PORTRAIT
            "nds", "dsi", "n3ds" -> 0.9f
            "psp" -> PSP_CASE
            "gamecube" -> if (location == Region.JAPAN) 0.8f else DVD_CASE
            "wii", "wiiu", "ps2" -> DVD_CASE
            in neutralComputers -> 1f // Cassette, disk, cartridge and big-box releases coexist.
            else -> 1f
        }
    }

    /** Known ROM suffixes win over metadata; namespaces resolve otherwise opaque archives. */
    fun platformId(systemId: String, filename: String, platform: String? = null): String {
        val extension = romExtension(filename)
        return when (systemId) {
            "nes" -> when (extension) {
                "fds" -> "fds"
                "nes", "unf", "unif" -> "nes"
                else -> if (matches(platform, "Nintendo - Family Computer Disk System")) "fds" else "nes"
            }
            "gb" -> when (extension) {
                "gbc" -> "gbc"
                "gb", "dmg" -> "gb"
                else -> if (matches(platform, "Nintendo - Game Boy Color")) "gbc" else "gb"
            }
            "megadrive" -> when (extension) {
                "sms" -> "mastersystem"
                "gg" -> "gamegear"
                "sg" -> "sg1000"
                "md", "gen", "smd", "68k" -> "megadrive"
                else -> when {
                    matches(platform, "Sega - Master System - Mark III") -> "mastersystem"
                    matches(platform, "Sega - Game Gear") -> "gamegear"
                    matches(platform, "Sega - SG-1000") -> "sg1000"
                    else -> "megadrive"
                }
            }
            "pce" -> when (extension) {
                "sgx" -> "supergrafx"
                "pce" -> "pce"
                else -> if (matches(platform, "NEC - PC Engine SuperGrafx")) "supergrafx" else "pce"
            }
            "ngp" -> when (extension) {
                "ngc" -> "ngpc"
                "ngp" -> "ngp"
                else -> if (matches(platform, "SNK - Neo Geo Pocket Color")) "ngpc" else "ngp"
            }
            "wswan" -> when (extension) {
                "wsc" -> "wswancolor"
                "ws" -> "wswan"
                else -> if (matches(platform, "Bandai - WonderSwan Color")) "wswancolor" else "wswan"
            }
            "nds" -> when (extension) {
                "dsi", "ids" -> "dsi"
                "nds" -> "nds"
                else -> if (matches(platform, "Nintendo - Nintendo DSi")) "dsi" else "nds"
            }
            else -> systemId
        }
    }

    private fun matches(value: String?, name: String): Boolean = value?.equals(name, ignoreCase = true) == true

    private fun romExtension(filename: String): String {
        var name = filename.substringAfterLast('/').substringAfterLast('\\')
        repeat(2) {
            if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in archiveExtensions)
                name = name.substringBeforeLast('.')
        }
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    /** Only explicit region tags, never title substrings such as "Japan Pro Golf". */
    private fun filenameRegion(filename: String): Region? {
        val regions = regionTags.findAll(filename.substringAfterLast('/').substringAfterLast('\\'))
            .flatMap { it.groupValues[1].ifEmpty { it.groupValues[2] }.split(',', '/', '+').asSequence() }
            .mapNotNull(::metadataRegion).toSet()
        return when {
            regions.size == 1 -> regions.first()
            regions.isNotEmpty() -> Region.OTHER // A multi-region filename is not a Japanese edition.
            else -> null
        }
    }

    private fun metadataRegion(value: String?): Region? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "jp", "jpn", "japan", "j" -> Region.JAPAN
        "us", "usa", "u", "united states", "north america", "eu", "eur", "e", "europe",
        "uk", "united kingdom", "de", "germany", "fr", "france", "it", "italy", "es", "spain",
        "au", "australia", "pal", "ntsc-u" -> Region.WESTERN
        "world", "w", "ww", "asia", "korea", "kr", "brazil", "br" -> Region.OTHER
        else -> null
    }
}

/** SQLite already contains every input; resolving this value cannot open a file or wake a NAS. */
val LibraryGame.coverAspectRatio: Float
    get() = GameCoverFormats.aspectRatio(systemId, filename, metadataRegion, coverWidth, coverHeight, metadataPlatform)

/** The cover for an image loader: an artwork file on this device, or a server image address (RomM). */
val LibraryGame.coverModel: Any? get() = coverPath?.let(::gameArtworkModel)

/** The background image for an image loader; see [coverModel]. */
val LibraryGame.backdropModel: Any? get() = backdropPath?.let(::gameArtworkModel)

fun gameArtworkModel(path: String): Any =
    if (path.startsWith("https://") || path.startsWith("http://")) path else java.io.File(path)
