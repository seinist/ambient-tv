package com.ambient.tv.ui.settings

import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/**
 * One vector material family for attached devices and emulated console pads.
 * No screenshots, per-brand color skins, bitmaps or independent focus animations.
 */
internal enum class InputArtworkShape { PAD, OVAL, THREE_GRIP, REMOTE, KEYBOARD, HANDHELD, DUAL_SCREEN, JOYSTICK, WII_REMOTE, SCREEN_PAD }
internal data class InputArtworkKey(
    val id: String, val label: String, val x: Float, val y: Float,
    val target: RetroPadButton? = null, val wide: Boolean = false,
)
internal data class InputArtwork(
    val title: String,
    val shape: InputArtworkShape,
    val keys: List<InputArtworkKey>,
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

internal fun attachedArtwork(kind: String, labels: GamepadLabels = GamepadLabels.XBOX): InputArtwork = when (kind) {
    "companion" -> InputArtwork("Handy-Controller", InputArtworkShape.SCREEN_PAD,
        snesKeys() + listOf(
            key("l2", "L2", .17f, .13f, RetroPadButton.L2),
            key("r2", "R2", .83f, .13f, RetroPadButton.R2),
            key("l3", "L3", .38f, .83f, RetroPadButton.L3),
            key("r3", "R3", .62f, .83f, RetroPadButton.R3),
        ))
    "remote" -> InputArtwork("Fernbedienung", InputArtworkShape.REMOTE, listOf(
        key("power", "⏻", .50f, .13f),
        key("back", "↩", .445f, .25f), key("menu", "☰", .555f, .25f),
        key("up", "▲", .50f, .39f), key("left", "◀", .435f, .49f),
        key("ok", "OK", .50f, .49f), key("right", "▶", .565f, .49f),
        key("down", "▼", .50f, .59f),
        key("home", "⌂", .445f, .69f), key("play", "▶Ⅱ", .555f, .69f),
        key("red", "R", .44f, .81f), key("green", "G", .48f, .81f),
        key("yellow", "Y", .52f, .81f), key("blue", "B", .56f, .81f),
    ))
    "keyboard" -> InputArtwork("Tastatur", InputArtworkShape.KEYBOARD, buildList {
        val first = listOf("Esc", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")
        first.forEachIndexed { i, name -> add(key(name, name, .1f + .8f * i / 12f, .23f)) }
        listOf("Q","W","E","R","T","Z","U","I","O","P").forEachIndexed { i, name ->
            add(key(name, name, .14f + .72f * i / 9f, .43f))
        }
        listOf("A","S","D","F","G","H","J","K","L").forEachIndexed { i, name ->
            add(key(name, name, .16f + .68f * i / 8f, .60f))
        }
        listOf("Y","X","C","V","B","N","M").forEachIndexed { i, name ->
            add(key(name, name, .25f + .5f * i / 6f, .76f))
        }
        add(key("Space", "Leertaste", .49f, .91f, wide = true))
        add(key("Enter", "↵", .9f, .60f))
    })
    else -> InputArtwork("Controller", InputArtworkShape.PAD, listOf(
        key("l2", labels.label(GamepadButton.L2), .20f, .13f),
        key("r2", labels.label(GamepadButton.R2), .80f, .13f),
        key("l1", labels.label(GamepadButton.L1), .27f, .23f),
        key("r1", labels.label(GamepadButton.R1), .73f, .23f),
        key("up", "▲", .28f, .43f),
        key("left", "◀", .20f, .54f),
        key("right", "▶", .36f, .54f),
        key("down", "▼", .28f, .65f),
        key("x", labels.label(GamepadButton.X), .67f, .54f),
        key("y", labels.label(GamepadButton.Y), .75f, .41f),
        key("a", labels.label(GamepadButton.A), .75f, .66f),
        key("b", labels.label(GamepadButton.B), .83f, .54f),
        key("select", labels.label(GamepadButton.SELECT), .435f, .39f),
        key("start", labels.label(GamepadButton.START), .565f, .39f),
        key("l3", labels.label(GamepadButton.THUMB_L), .39f, .77f),
        key("r3", labels.label(GamepadButton.THUMB_R), .61f, .77f),
        key("mode", "●", .5f, .6f),
    ))
}

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
    key("start", start, .57f, .75f, RetroPadButton.START, wide = start.length > 3),
    key("select", select, .45f, .75f, RetroPadButton.SELECT, wide = select.length > 3),
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
    key("b", "Fire", .76f, .53f, RetroPadButton.B, wide = true),
    key("a", "Fire 2", .87f, .53f, RetroPadButton.A, wide = true),
    key("select", "Shift", .42f, .78f, RetroPadButton.SELECT, wide = true),
    key("start", "Return", .60f, .78f, RetroPadButton.START, wide = true),
)

/**
 * Core platform IDs are resolved by GameSystems before this call.
 * Templates show familiar *positions*, not guaranteed core-provided button descriptors.
 * All 31 registered systems have explicit artwork; a generic fallback handles future cores.
 */
internal fun consoleArtwork(systemId: String, variant: String = ""): InputArtwork {
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
                key("up", "▲", .19f, .46f, RetroPadButton.UP),
                key("down", "▼", .19f, .65f, RetroPadButton.DOWN),
                key("left", "◀", .13f, .56f, RetroPadButton.LEFT),
                key("right", "▶", .25f, .56f, RetroPadButton.RIGHT),
                key("y", "Y", .76f, .56f, RetroPadButton.Y),
                key("x", "X", .83f, .45f, RetroPadButton.X),
                key("b", "B", .83f, .67f, RetroPadButton.B),
                key("a", "A", .90f, .56f, RetroPadButton.A),
                key("start", "+", .59f, .83f, RetroPadButton.START),
                key("select", "−", .41f, .83f, RetroPadButton.SELECT),
                key("l", "L", .20f, .16f, RetroPadButton.L),
                key("r", "R", .80f, .16f, RetroPadButton.R),
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
                key("up", "▲", .50f, .25f, RetroPadButton.UP),
                key("left", "◀", .45f, .35f, RetroPadButton.LEFT),
                key("right", "▶", .55f, .35f, RetroPadButton.RIGHT),
                key("down", "▼", .50f, .45f, RetroPadButton.DOWN),
                key("a", "A", .50f, .53f, RetroPadButton.A),
                key("b", "B", .50f, .63f, RetroPadButton.B),
                key("start", "+", .50f, .73f, RetroPadButton.START),
                key("select", "−", .50f, .81f, RetroPadButton.SELECT),
                key("l", if (variant == "nunchuk") "C" else "1", .45f, .91f, RetroPadButton.L),
                key("r", if (variant == "nunchuk") "Z" else "2", .55f, .91f, RetroPadButton.R),
            ))
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

