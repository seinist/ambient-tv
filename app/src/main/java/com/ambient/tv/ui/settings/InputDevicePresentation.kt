package com.ambient.tv.ui.settings

import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/**
 * One vector material family for attached devices and emulated console pads.
 * No screenshots, per-brand color skins, bitmaps or independent focus animations.
 */
internal enum class InputArtworkShape { PAD, OVAL, THREE_GRIP, REMOTE, KEYBOARD, HANDHELD, DUAL_SCREEN, JOYSTICK, WII_REMOTE, SCREEN_PAD }
internal data class InputArtworkKey(
    val id: String, val label: String, val x: Float, val y: Float,
    val target: RetroPadButton? = null, val wide: Boolean = false,
    /** Both dimensions use the viewport WIDTH as their unit, keeping round keys round. */
    val widthFraction: Float = if (wide) .13f else .06f,
    val heightFraction: Float = .06f,
    /** Buttons outside the front view (e.g. rear triggers) get separate accessible controls. */
    val auxiliary: Boolean = false,
)
internal data class InputArtwork(
    val title: String,
    val shape: InputArtworkShape,
    val keys: List<InputArtworkKey>,
    val aspectRatio: Float = defaultArtworkAspectRatio(shape),
    val vectorId: String? = null,
)

private fun key(id: String, label: String, x: Float, y: Float, target: RetroPadButton? = null, wide: Boolean = false) =
    InputArtworkKey(id, label, x, y, target, wide)

private fun direction() = listOf(
    key("up", "▲", .255f, .39f, RetroPadButton.UP),
    key("down", "▼", .255f, .66f, RetroPadButton.DOWN),
    key("left", "◀", .185f, .525f, RetroPadButton.LEFT),
    key("right", "▶", .325f, .525f, RetroPadButton.RIGHT),
)

private fun snesKeys() = direction() + listOf(
    key("y", "Y", .672f, .535f, RetroPadButton.Y),
    key("x", "X", .755f, .395f, RetroPadButton.X),
    key("b", "B", .755f, .67f, RetroPadButton.B),
    key("a", "A", .84f, .535f, RetroPadButton.A),
    key("l", "L", .25f, .21f, RetroPadButton.L),
    key("r", "R", .75f, .21f, RetroPadButton.R),
    key("select", "−", .455f, .63f, RetroPadButton.SELECT),
    key("start", "+", .55f, .63f, RetroPadButton.START),
)

/** Every registered console system uses the same vector materials, not a distinct art style. */
internal val supportedConsoleArtworkIds: Set<String> = setOf(
    "nes", "snes", "gb", "gba", "megadrive", "pce", "ngp", "wswan", "lynx",
    "a2600", "vb", "n64", "psx", "nds", "n3ds", "gamecube", "wii", "wiiu",
    "psp", "dreamcast", "saturn", "ps2", "c64", "c128", "vic20", "plus4",
    "pet", "cbm2", "a7800", "a5200", "sega32x",
)

/**
 * A grouped system can expose multiple **visual** controller templates.
 * The emulator core, not this illustration, owns the actual Libretro controller mode/options.
 */
internal fun consoleArtworkVariants(systemId: String): List<Pair<String, String>> = when (systemId) {
    "megadrive" -> listOf(
        "md6" to "Mega Drive · 6 Tasten",
        "md3" to "Mega Drive · 3 Tasten",
        "sms" to "Master System · 2 Tasten",
        "gg" to "Game Gear",
    )
    "sega32x" -> listOf(
        "md6" to "Mega Drive · 6 Tasten",
        "md3" to "Mega Drive · 3 Tasten",
    )
    "pce" -> listOf("pce2" to "PC Engine · 2 Tasten", "pce6" to "PC Engine · 6 Tasten")
    "wii" -> listOf("remote" to "Wii Remote", "nunchuk" to "Wii Remote + Nunchuk")
    else -> emptyList()
}

private fun retroPair(left: String = "B", right: String = "A") = listOf(
    key("b", left, .735f, .62f, RetroPadButton.B),
    key("a", right, .835f, .48f, RetroPadButton.A),
)
private fun shoulderPair(l: String = "L", r: String = "R") = listOf(
    key("l", l, .24f, .22f, RetroPadButton.L),
    key("r", r, .76f, .22f, RetroPadButton.R),
)
private fun startSelect(start: String = "+", select: String = "−") = listOf(
    key("start", start, .58f, .75f, RetroPadButton.START, wide = start.length > 3),
    key("select", select, .42f, .75f, RetroPadButton.SELECT, wide = select.length > 3),
)
private fun fourFace(a: String = "A", b: String = "B", x: String = "X", y: String = "Y") = listOf(
    key("y", y, .68f, .54f, RetroPadButton.Y),
    key("x", x, .76f, .40f, RetroPadButton.X),
    key("b", b, .76f, .67f, RetroPadButton.B),
    key("a", a, .84f, .54f, RetroPadButton.A),
)
private fun sixFace(a: String = "A", b: String = "B", c: String = "C",
                    x: String = "X", y: String = "Y", z: String = "Z") = listOf(
    key("y", a, .625f, .69f, RetroPadButton.Y),
    key("b", b, .745f, .69f, RetroPadButton.B),
    key("a", c, .865f, .69f, RetroPadButton.A),
    key("l", x, .625f, .44f, RetroPadButton.L),
    key("x", y, .745f, .44f, RetroPadButton.X),
    key("r", z, .865f, .44f, RetroPadButton.R),
)
private fun retroKeyRows(): List<InputArtworkKey> = listOf(
    key("up", "↑", .23f, .35f, RetroPadButton.UP),
    key("left", "←", .15f, .49f, RetroPadButton.LEFT),
    key("right", "→", .31f, .49f, RetroPadButton.RIGHT),
    key("down", "↓", .23f, .64f, RetroPadButton.DOWN),
    key("b", "Fire", .73f, .53f, RetroPadButton.B, wide = true),
    key("a", "Fire 2", .90f, .53f, RetroPadButton.A, wide = true),
    key("select", "Shift", .42f, .78f, RetroPadButton.SELECT, wide = true),
    key("start", "Return", .60f, .78f, RetroPadButton.START, wide = true),
)

/**
 * Core platform IDs are resolved by GameSystems before this call.
 * Templates show familiar *positions*, not guaranteed core-provided button descriptors.
 * All 31 registered systems have explicit artwork; a generic fallback handles future cores.
 */
internal fun consoleArtwork(systemId: String, variant: String = ""): InputArtwork =
    originalConsoleArtwork(systemId, variant).withConsoleVector(systemId, variant)

private fun originalConsoleArtwork(systemId: String, variant: String = ""): InputArtwork {
    val title = when (systemId) {
        "nes" -> "Nintendo Entertainment System"
        "snes" -> "Super Nintendo"
        "gb" -> "Game Boy / Game Boy Color"
        "gba" -> "Game Boy Advance"
        "megadrive" -> "Sega Mega Drive / Master System / Game Gear"
        "pce" -> "PC Engine"
        "ngp" -> "Neo Geo Pocket"
        "wswan" -> "WonderSwan"
        "lynx" -> "Atari Lynx"
        "a2600" -> "Atari 2600"
        "vb" -> "Virtual Boy"
        "n64" -> "Nintendo 64"
        "psx" -> "PlayStation"
        "nds" -> "Nintendo DS"
        "n3ds" -> "Nintendo 3DS"
        "gamecube" -> "Nintendo GameCube"
        "wii" -> "Nintendo Wii"
        "wiiu" -> "Nintendo Wii U"
        "psp" -> "PlayStation Portable"
        "dreamcast" -> "Sega Dreamcast"
        "saturn" -> "Sega Saturn"
        "ps2" -> "PlayStation 2"
        "c64" -> "Commodore 64"
        "c128" -> "Commodore 128"
        "vic20" -> "Commodore VIC-20"
        "plus4" -> "Commodore Plus/4"
        "pet" -> "Commodore PET"
        "cbm2" -> "Commodore CBM-II"
        "a7800" -> "Atari 7800"
        "a5200" -> "Atari 5200"
        "sega32x" -> "Sega 32X"
        else -> systemId.ifBlank { "Libretro RetroPad" }
    }
    return when (systemId) {
        "nes" -> InputArtwork(title, InputArtworkShape.OVAL,
            direction() + retroPair() + startSelect(start = "Start", select = "Select"))
        "snes" -> InputArtwork(title, InputArtworkShape.OVAL, snesKeys())
        "gb" -> InputArtwork(title, InputArtworkShape.HANDHELD, direction() + retroPair()
            + startSelect(start = "Start", select = "Select"))
        "gba" -> InputArtwork(title, InputArtworkShape.HANDHELD, direction() + retroPair()
            + shoulderPair() + startSelect())
        "megadrive", "sega32x" -> when (variant) {
            "md3" -> InputArtwork("$title · 3 Tasten", InputArtworkShape.OVAL,
                direction() + sixFace().filter { it.id in setOf("y", "b", "a") } +
                listOf(key("start", "Start", .49f, .78f, RetroPadButton.START, true)))
            "sms" -> InputArtwork("Master System", InputArtworkShape.OVAL,
                direction() + retroPair("1", "2") + startSelect())
            "gg" -> InputArtwork("Game Gear", InputArtworkShape.HANDHELD,
                direction() + retroPair("1", "2") + startSelect())
            else -> InputArtwork("$title · 6 Tasten", InputArtworkShape.OVAL,
                direction() + sixFace() +
                listOf(key("start", "Start", .49f, .78f, RetroPadButton.START, true)))
        }
        "pce" -> if (variant == "pce6") InputArtwork("$title · 6 Tasten", InputArtworkShape.OVAL,
            direction() + sixFace("III", "II", "I", "VI", "V", "IV") + startSelect())
        else InputArtwork(title, InputArtworkShape.OVAL,
            direction() + retroPair("II", "I") + startSelect())
        "ngp" -> InputArtwork(title, InputArtworkShape.HANDHELD,
            direction() + retroPair() + startSelect(start = "Option", select = ""))
        "wswan" -> InputArtwork(title, InputArtworkShape.HANDHELD, listOf(
            key("up", "X▲", .25f, .38f, RetroPadButton.UP),
            key("left", "X◀", .17f, .49f, RetroPadButton.LEFT),
            key("right", "X▶", .33f, .49f, RetroPadButton.RIGHT),
            key("down", "X▼", .25f, .61f, RetroPadButton.DOWN),
            key("x", "Y▲", .47f, .38f, RetroPadButton.X),
            key("y", "Y◀", .40f, .49f, RetroPadButton.Y),
        ) + retroPair() + startSelect())
        "lynx" -> InputArtwork(title, InputArtworkShape.HANDHELD,
            direction() + retroPair("A", "B") + startSelect("Option 1", "Option 2"))
        "a2600" -> InputArtwork(title, InputArtworkShape.JOYSTICK, listOf(
            key("up", "↑", .41f, .25f, RetroPadButton.UP),
            key("left", "←", .30f, .37f, RetroPadButton.LEFT),
            key("right", "→", .52f, .37f, RetroPadButton.RIGHT),
            key("down", "↓", .41f, .48f, RetroPadButton.DOWN),
            key("b", "Fire", .74f, .72f, RetroPadButton.B, true),
            key("start", "Start", .46f, .80f, RetroPadButton.START, true),
        ))
        "vb" -> InputArtwork(title, InputArtworkShape.OVAL,
            direction() + fourFace() + shoulderPair() + startSelect())
        "n64" -> InputArtwork(title, InputArtworkShape.THREE_GRIP, direction() + listOf(
            key("b", "B", .70f, .63f, RetroPadButton.B),
            key("a", "A", .83f, .66f, RetroPadButton.A),
            key("x", "C▲", .84f, .40f, RetroPadButton.X),
            key("y", "C◀", .75f, .43f, RetroPadButton.Y),
            key("l", "L", .22f, .20f, RetroPadButton.L),
            key("r", "R", .78f, .20f, RetroPadButton.R),
            key("l2", "Z", .51f, .80f, RetroPadButton.L2),
            key("start", "Start", .51f, .37f, RetroPadButton.START, true),
        ))
        "psx", "ps2" -> InputArtwork(title, InputArtworkShape.PAD,
            direction() + fourFace(a = "○", b = "✕", x = "△", y = "□")
            + shoulderPair("L1", "R1") + listOf(
                key("l2", "L2", .21f, .12f, RetroPadButton.L2),
                key("r2", "R2", .79f, .12f, RetroPadButton.R2),
                key("start", "Start", .56f, .38f, RetroPadButton.START, true),
                key("select", "Select", .44f, .38f, RetroPadButton.SELECT, true),
            ))
        "nds", "n3ds" -> InputArtwork(title, InputArtworkShape.DUAL_SCREEN,
            listOf(
                key("up", "▲", .19f, .62f, RetroPadButton.UP),
                key("down", "▼", .19f, .76f, RetroPadButton.DOWN),
                key("left", "◀", .11f, .69f, RetroPadButton.LEFT),
                key("right", "▶", .27f, .69f, RetroPadButton.RIGHT),
                key("y", "Y", .73f, .69f, RetroPadButton.Y),
                key("x", "X", .81f, .62f, RetroPadButton.X),
                key("b", "B", .81f, .76f, RetroPadButton.B),
                key("a", "A", .89f, .69f, RetroPadButton.A),
                key("start", "+", .59f, .89f, RetroPadButton.START),
                key("select", "−", .41f, .89f, RetroPadButton.SELECT),
                key("l", "L", .20f, .16f, RetroPadButton.L).copy(auxiliary = true),
                key("r", "R", .80f, .16f, RetroPadButton.R).copy(auxiliary = true),
            ))
        "gamecube" -> InputArtwork(title, InputArtworkShape.PAD,
            direction() + listOf(
                key("b", "B", .70f, .63f, RetroPadButton.B),
                key("a", "A", .81f, .56f, RetroPadButton.A),
                key("x", "X", .88f, .46f, RetroPadButton.X),
                key("y", "Y", .75f, .40f, RetroPadButton.Y),
                key("l", "L", .20f, .22f, RetroPadButton.L),
                key("r", "R", .80f, .22f, RetroPadButton.R),
                key("r2", "Z", .87f, .18f, RetroPadButton.R2),
                key("start", "Start", .50f, .48f, RetroPadButton.START, true),
            ))
        "wii" -> InputArtwork(if (variant == "nunchuk") "$title · Nunchuk" else "$title · Remote",
            InputArtworkShape.WII_REMOTE, listOf(
                key("up", "▲", .50f, .265f, RetroPadButton.UP),
                key("left", "◀", .25f, .34f, RetroPadButton.LEFT),
                key("right", "▶", .75f, .34f, RetroPadButton.RIGHT),
                key("down", "▼", .50f, .415f, RetroPadButton.DOWN),
                key("a", "A", .50f, .53f, RetroPadButton.A),
                key("b", "B", .50f, .63f, RetroPadButton.B).copy(auxiliary = true),
                key("start", "+", .70f, .68f, RetroPadButton.START),
                key("select", "−", .30f, .68f, RetroPadButton.SELECT),
                key("l", if (variant == "nunchuk") "C" else "1", .50f, .79f, RetroPadButton.L)
                    .copy(auxiliary = variant == "nunchuk"),
                key("r", if (variant == "nunchuk") "Z" else "2", .50f, .89f, RetroPadButton.R)
                    .copy(auxiliary = variant == "nunchuk"),
            ).map { it.copy(widthFraction = .18f, heightFraction = .18f) })
        "wiiu" -> InputArtwork(title, InputArtworkShape.SCREEN_PAD,
            direction() + fourFace() + shoulderPair() + startSelect())
        "psp" -> InputArtwork(title, InputArtworkShape.HANDHELD,
            direction() + fourFace(a = "○", b = "✕", x = "△", y = "□")
                + shoulderPair() + startSelect("Start", "Select"))
        "dreamcast" -> InputArtwork(title, InputArtworkShape.SCREEN_PAD,
            direction() + fourFace() + shoulderPair("L", "R") + startSelect("Start", ""))
        "saturn" -> InputArtwork(title, InputArtworkShape.OVAL,
            // Six face buttons already occupy RetroPad L/R; keep the two actual Saturn
            // shoulders visually distinct and addressable as the available L2/R2 slots.
            direction() + sixFace() + listOf(
                key("l2", "L", .24f, .22f, RetroPadButton.L2),
                key("r2", "R", .76f, .22f, RetroPadButton.R2),
            ) + startSelect("Start", ""))
        "c64", "c128", "vic20", "plus4", "pet", "cbm2" ->
            InputArtwork("$title · Tastatur/Joystick", InputArtworkShape.KEYBOARD, retroKeyRows())
        "a7800" -> InputArtwork(title, InputArtworkShape.JOYSTICK, listOf(
            key("up", "↑", .41f, .25f, RetroPadButton.UP),
            key("left", "←", .30f, .37f, RetroPadButton.LEFT),
            key("right", "→", .52f, .37f, RetroPadButton.RIGHT),
            key("down", "↓", .41f, .48f, RetroPadButton.DOWN),
            key("b", "Fire 1", .68f, .72f, RetroPadButton.B, true),
            key("a", "Fire 2", .82f, .72f, RetroPadButton.A, true),
            key("start", "Start", .48f, .83f, RetroPadButton.START, true),
        ))
        "a5200" -> InputArtwork(title, InputArtworkShape.JOYSTICK, listOf(
            key("up", "↑", .40f, .22f, RetroPadButton.UP),
            key("left", "←", .28f, .35f, RetroPadButton.LEFT),
            key("right", "→", .52f, .35f, RetroPadButton.RIGHT),
            key("down", "↓", .40f, .48f, RetroPadButton.DOWN),
            key("b", "Fire", .71f, .70f, RetroPadButton.B, true),
            key("a", "Fire 2", .84f, .70f, RetroPadButton.A, true),
            key("start", "Start", .46f, .83f, RetroPadButton.START, true),
        ))
        else -> InputArtwork(title, InputArtworkShape.PAD, snesKeys())
    }
}

