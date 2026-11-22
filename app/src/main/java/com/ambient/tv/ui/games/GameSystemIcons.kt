package com.ambient.tv.ui.games

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.ambient.tv.R

/**
 * Native Android vectors from RetroArch's XMB Monochrome theme (CC BY 4.0), with the original
 * Ambient TV C128 artwork retained because upstream provides no corresponding SVG source.
 * Controller, handheld and computer silhouettes keep the source geometry and use the current
 * Ambient TV theme's tint. Provenance and reproduction instructions are recorded in
 * third_party_assets/game_system_icons; attribution is also available in the About settings.
 */
object GameSystemIcons {
    val supportedSystemIds: Set<String> = setOf(
        "nes", "snes", "gb", "gba", "megadrive", "pce", "ngp", "wswan", "lynx", "a2600", "vb", "n64", "psx", "nds", "n3ds", "gamecube", "wii", "wiiu", "psp", "dreamcast", "saturn", "ps2", "c64", "c128", "vic20", "plus4", "pet", "cbm2", "a7800", "a5200", "sega32x"
    )

    @DrawableRes
    fun drawable(systemId: String): Int = when (systemId) {
        "nes" -> R.drawable.game_system_nes
        "snes" -> R.drawable.game_system_snes
        "gb" -> R.drawable.game_system_gb
        "gba" -> R.drawable.game_system_gba
        "megadrive" -> R.drawable.game_system_megadrive
        "pce" -> R.drawable.game_system_pce
        "ngp" -> R.drawable.game_system_ngp
        "wswan" -> R.drawable.game_system_wswan
        "lynx" -> R.drawable.game_system_lynx
        "a2600" -> R.drawable.game_system_a2600
        "vb" -> R.drawable.game_system_vb
        "n64" -> R.drawable.game_system_n64
        "psx" -> R.drawable.game_system_psx
        "nds" -> R.drawable.game_system_nds
        "n3ds" -> R.drawable.game_system_n3ds
        "gamecube" -> R.drawable.game_system_gamecube
        "wii" -> R.drawable.game_system_wii
        "wiiu" -> R.drawable.game_system_wiiu
        "psp" -> R.drawable.game_system_psp
        "dreamcast" -> R.drawable.game_system_dreamcast
        "saturn" -> R.drawable.game_system_saturn
        "ps2" -> R.drawable.game_system_ps2
        "c64" -> R.drawable.game_system_c64
        "c128" -> R.drawable.game_system_c128
        "vic20" -> R.drawable.game_system_vic20
        "plus4" -> R.drawable.game_system_plus4
        "pet" -> R.drawable.game_system_pet
        "cbm2" -> R.drawable.game_system_cbm2
        "a7800" -> R.drawable.game_system_a7800
        "a5200" -> R.drawable.game_system_a5200
        "sega32x" -> R.drawable.game_system_sega32x
        else -> R.drawable.game_library_console
    }
}

@Composable
fun gameSystemVector(systemId: String): ImageVector =
    ImageVector.vectorResource(GameSystemIcons.drawable(systemId))
