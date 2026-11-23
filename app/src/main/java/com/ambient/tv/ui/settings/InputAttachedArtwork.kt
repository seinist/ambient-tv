package com.ambient.tv.ui.settings

import com.ambient.tv.ui.gamepad.ControllerModel
import com.ambient.tv.ui.gamepad.GamepadButton
import com.ambient.tv.ui.gamepad.GamepadLabels
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import java.util.Locale

/** The connected device is independent of the emulated console's target controller. */
internal fun attachedArtwork(
    kind: String,
    labels: GamepadLabels = GamepadLabels.GENERIC,
    controllerModel: ControllerModel? = null,
): InputArtwork = when (kind) {
    "remote" -> remoteArtwork()
    "keyboard" -> keyboardArtwork()
    "companion" -> phoneControllerArtwork()
    else -> physicalControllerArtwork(controllerModel ?: when (labels) {
        GamepadLabels.PLAYSTATION -> ControllerModel.PLAYSTATION
        GamepadLabels.NINTENDO -> ControllerModel.NINTENDO
        GamepadLabels.XBOX -> ControllerModel.XBOX
        GamepadLabels.GENERIC -> ControllerModel.GENERIC
    })
}

private fun physicalControllerArtwork(model: ControllerModel): InputArtwork {
    val keys = GamepadButton.entries.map { button ->
        InputArtworkKey(button.key, model.label(button), .5f, .5f, auxiliary = true)
    } + listOf(
        InputArtworkKey("up", "▲", .26f, .42f), InputArtworkKey("down", "▼", .26f, .60f),
        InputArtworkKey("left", "◀", .20f, .51f), InputArtworkKey("right", "▶", .32f, .51f),
    )
    val original = InputArtwork("Controller", InputArtworkShape.PAD, keys)
    // Family-only detection uses a representative shape, while the selector explicitly names
    // the family. Unknown hardware keeps an original, unbranded silhouette.
    val assetId = model.assetKey ?: when (model) {
        ControllerModel.XBOX -> "xbox-one"
        ControllerModel.PLAYSTATION -> "ps4"
        ControllerModel.NINTENDO -> "switch-pro"
        else -> null
    }
    if (assetId == null) {
        // Flat original pad: sticks and d-pad sit in recessed wells, face buttons on the body.
        // Sizes are in viewport widths (see InputArtworkKey), centers in viewport fractions.
        val anchors = mapOf(
            "x" to Triple(.70f, .37f, .072f), "y" to Triple(.78f, .24f, .072f),
            "a" to Triple(.78f, .50f, .072f), "b" to Triple(.86f, .37f, .072f),
            "l3" to Triple(.20f, .36f, .115f), "r3" to Triple(.64f, .58f, .115f),
            "select" to Triple(.44f, .38f, .042f), "start" to Triple(.56f, .38f, .042f),
            "mode" to Triple(.50f, .27f, .052f),
            "up" to Triple(.33f, .49f, .048f), "down" to Triple(.33f, .67f, .048f),
            "left" to Triple(.27f, .58f, .048f), "right" to Triple(.39f, .58f, .048f),
        )
        return original.copy(
            keys = keys.map { entry ->
                anchors[entry.id]?.let { (x, y, size) ->
                    entry.copy(x = x, y = y, widthFraction = size, heightFraction = size, auxiliary = false,
                        plain = entry.id in setOf("up", "down", "left", "right"))
                } ?: entry
            },
            wells = listOf(ArtworkWell(.20f, .36f, .085f), ArtworkWell(.64f, .58f, .085f), ArtworkWell(.33f, .58f, .095f)),
            dpad = ArtworkWell(.33f, .58f, .084f),
        )
    }
    return original.withControllerVector(assetId, physicalControllerAnchors(assetId))
}

private fun physicalControllerAnchors(id: String): Map<String, ControllerKeyAnchor> {
    fun a(x: Float, y: Float, size: Float = 4.5f) = ControllerKeyAnchor(x, y, size)
    // These are individual button centers measured from the pinned source paths, not a
    // shared diamond: the generations have different spacing and several are asymmetric.
    // Physical event IDs are x=left, y=top, a=bottom, b=right; labels stay model-specific.
    return when (id) {
        "xbox-360" -> directionAnchors(22.12f, 32.63f, 3.3f, 3.3f) + mapOf(
            "x" to a(45.90f, 21.08f), "y" to a(50.97f, 16f),
            "a" to a(50.91f, 26.16f), "b" to a(56f, 21.07f),
            "l3" to a(13.09f, 21.08f, 7f), "r3" to a(41.12f, 32.63f, 7f),
            "select" to ControllerKeyAnchor(25.02f, 21.45f, 3.5f, 2.6f),
            "start" to ControllerKeyAnchor(38.92f, 21.50f, 3.5f, 2.6f),
            "mode" to a(32f, 21.44f, 5.5f),
        )
        "xbox-one" -> directionAnchors(23.49f, 32.25f, 3.1f, 3.0f) + mapOf(
            "x" to a(44.38f, 21.86f, 4.4f), "y" to a(48.84f, 17.46f, 4.4f),
            "a" to a(48.84f, 26.19f, 4.4f), "b" to a(53.23f, 21.86f, 4.4f),
            "l3" to a(15.41f, 21.59f, 7f), "r3" to a(40.57f, 31.55f, 7f),
            "select" to a(27.41f, 21.67f, 3f), "start" to a(36.80f, 21.67f, 3f),
            "mode" to a(31.97f, 15.15f, 4.5f),
        )
        "xbox-series-x" -> directionAnchors(23.5f, 32.31f, 2.8f, 2.9f) + mapOf(
            "x" to a(44.08f, 21.98f, 4.4f), "y" to a(48.39f, 17.71f, 4.4f),
            "a" to a(48.39f, 26.25f, 4.4f), "b" to a(52.67f, 21.98f, 4.4f),
            "l3" to a(15.61f, 21.9f, 7f), "r3" to a(40.38f, 31.47f, 7f),
            "select" to a(27.38f, 21.82f, 2.7f), "start" to a(36.70f, 21.82f, 2.7f),
            "mode" to a(32f, 15.44f, 4.5f),
        )
        "ps3" -> directionAnchors(12.84f, 26.38f, 3.1f, 3f) + mapOf(
            "x" to a(45.86f, 26.36f, 4.3f), "y" to a(50.78f, 21.41f, 4.3f),
            "a" to a(50.78f, 31.06f, 4.3f), "b" to a(55.49f, 26.36f, 4.3f),
            "l3" to a(22.61f, 35.82f, 7f), "r3" to a(41.11f, 35.82f, 7f),
            "select" to ControllerKeyAnchor(26.33f, 26.49f, 3f, 2f),
            "start" to ControllerKeyAnchor(37.65f, 26.41f, 3f, 2f),
            "mode" to a(32f, 32f, 3.8f),
        )
        "ps4" -> directionAnchors(11.91f, 24.18f, 3.05f, 3.3f) + mapOf(
            "x" to a(47.46f, 24.15f, 4.4f), "y" to a(52.07f, 19.40f, 4.4f),
            "a" to a(52.16f, 28.93f, 4.4f), "b" to a(56.92f, 24.15f, 4.4f),
            "l3" to a(21.66f, 32.95f, 7f), "r3" to a(42.37f, 32.95f, 7f),
            "select" to ControllerKeyAnchor(18.83f, 17.81f, 2.1f, 3.6f),
            "start" to ControllerKeyAnchor(45.17f, 17.81f, 2.1f, 3.6f),
            "mode" to a(31.96f, 32.19f, 3.4f),
        )
        "ps5" -> directionAnchors(12.08f, 23f, 3.05f, 3.2f) + mapOf(
            "x" to a(47.26f, 22.96f, 4.1f), "y" to a(51.94f, 18.26f, 4.1f),
            "a" to a(51.94f, 27.67f, 4.1f), "b" to a(56.63f, 22.96f, 4.1f),
            "l3" to a(21.75f, 31.76f, 7f), "r3" to a(42.24f, 31.76f, 7f),
            "select" to ControllerKeyAnchor(17f, 15.94f, 1.7f, 2.9f),
            "start" to ControllerKeyAnchor(46.89f, 15.95f, 1.7f, 2.9f),
            // PS is between the sticks, above the microphone button shown by the source.
            "mode" to a(32f, 30f, 3.2f),
        )
        "switch-pro" -> directionAnchors(22.07f, 31.74f, 3.2f, 3.2f) + mapOf(
            "x" to a(43.92f, 22.99f), "y" to a(48.95f, 18.67f),
            "a" to a(48.95f, 27.31f), "b" to a(53.98f, 22.99f),
            "l3" to a(14.30f, 22.99f, 7f), "r3" to a(40.40f, 31.73f, 7f),
            "select" to a(24.01f, 18.13f, 2.8f), "start" to a(39.86f, 18.13f, 2.8f),
            "mode" to a(36.49f, 22.87f, 2.8f),
        )
        "joy-cons" -> mapOf(
            "up" to a(14.72f, 30.22f, 3.4f), "down" to a(14.72f, 37.06f, 3.4f),
            "left" to a(11.15f, 33.67f, 3.4f), "right" to a(18.28f, 33.67f, 3.4f),
            "x" to a(45.73f, 20.96f, 3.4f), "y" to a(49.28f, 17.53f, 3.4f),
            "a" to a(49.28f, 24.33f, 3.4f), "b" to a(52.83f, 20.96f, 3.4f),
            "l3" to a(14.71f, 20.97f, 6.5f), "r3" to a(49.28f, 33.61f, 6.5f),
            "start" to a(44.18f, 13.94f, 2.3f), "mode" to a(46.18f, 42.30f, 3.5f),
            // The thin minus slot is too small for a legible on-device label.
        )
        "joy-con-l" -> mapOf(
            "up" to a(27.47f, 29.55f, 4.4f), "down" to a(27.47f, 38.99f, 4.4f),
            "left" to a(22.54f, 34.31f, 4.4f), "right" to a(32.39f, 34.31f, 4.4f),
            "l3" to a(27.47f, 16.77f, 8f),
            // As in the pair image, Select/minus stays in the full-size key strip.
        )
        "joy-con-r" -> mapOf(
            "x" to a(31.62f, 16.75f), "y" to a(36.52f, 12.01f),
            "a" to a(36.52f, 21.41f), "b" to a(41.43f, 16.75f),
            "r3" to a(36.52f, 34.22f, 8f), "start" to a(29.48f, 7.06f, 3.2f),
            "mode" to a(32.25f, 46.22f, 4f),
        )
        "snes" -> directionAnchors(13.68f, 32.57f, 3.35f, 3.2f) + mapOf(
            "x" to a(43.63f, 32.47f, 5f), "y" to a(50.45f, 27.12f, 5f),
            "a" to a(50.52f, 37.77f, 5f), "b" to a(57.34f, 32.41f, 5f),
            "select" to a(25.94f, 34.61f, 3.2f), "start" to a(33.05f, 34.62f, 3.2f),
        )
        "n64" -> directionAnchors(11f, 20.95f, 3.3f, 3.2f) + mapOf(
            "start" to a(32f, 22.82f),
            // The N64 stick is not an L3 button. Unmatched bindings remain auxiliary.
        )
        else -> emptyMap()
    }
}

private fun remoteArtwork(): InputArtwork {
    // Viewport is 0.4 wide : 1 high, so a width-unit size s spans s / 2.5 of the height.
    fun k(id: String, text: String, x: Float, y: Float, size: Float = .2f, plain: Boolean = false) =
        InputArtworkKey(id, text, x, y, widthFraction = size, heightFraction = size, plain = plain)
    fun color(id: String, text: String, x: Float, argb: Long) =
        InputArtworkKey(id, text, x, .72f, widthFraction = .15f, heightFraction = .07f, color = argb)
    val ringY = .27f
    return InputArtwork("Fernbedienung", InputArtworkShape.REMOTE, listOf(
        k("power", "⏻", .50f, .055f, .15f),
        k("up", "▲", .50f, ringY - .096f, .14f, plain = true), k("down", "▼", .50f, ringY + .096f, .14f, plain = true),
        k("left", "◀", .26f, ringY, .14f, plain = true), k("right", "▶", .74f, ringY, .14f, plain = true),
        k("ok", "OK", .50f, ringY, .30f),
        k("back", "↩", .30f, .50f), k("home", "⌂", .70f, .50f),
        k("play", "▶Ⅱ", .30f, .60f), k("menu", "☰", .70f, .60f),
        color("red", "R", .20f, 0xFFE04848), color("green", "G", .40f, 0xFF3FB75E),
        color("yellow", "Y", .60f, 0xFFE4C23A), color("blue", "B", .80f, 0xFF3B7FE0),
    ), wells = listOf(ArtworkWell(.50f, ringY, .38f)))
}

/** A compact physical keyboard with real rows; its keys never overflow the housing. */
private fun keyboardArtwork(): InputArtwork {
    val german = Locale.getDefault().language == "de"
    fun k(id: String, text: String, x: Float, y: Float, width: Float = .050f) =
        InputArtworkKey(id, text, x, y, widthFraction = width, heightFraction = .034f)
    val keys = buildList {
        add(k("Esc", "Esc", .05f, .12f))
        (1..12).forEach { i -> add(k("F$i", "F$i", .16f + (i - 1) * .058f, .12f)) }
        "1234567890".forEachIndexed { i, c -> add(k(c.toString(), c.toString(), .07f + i * .062f, .30f)) }
        add(k("Backspace", "⌫", .755f, .30f, .145f))
        add(k("Tab", "⇥", .052f, .48f, .055f))
        (if (german) "QWERTZUIOP" else "QWERTYUIOP").forEachIndexed { i, c ->
            add(k(c.toString(), c.toString(), .12f + i * .062f, .48f))
        }
        add(k("Enter", "↵", .79f, .57f, .07f))
        add(k("Caps", "⇪", .06f, .65f, .075f))
        "ASDFGHJKL".forEachIndexed { i, c -> add(k(c.toString(), c.toString(), .15f + i * .062f, .65f)) }
        add(k("Shift", "⇧", .08f, .82f, .11f))
        (if (german) "YXCVBNM" else "ZXCVBNM").forEachIndexed { i, c ->
            add(k(c.toString(), c.toString(), .19f + i * .062f, .82f))
        }
        add(k("Space", "Space", .44f, .96f, .32f))
        add(k("Ctrl", "Ctrl", .10f, .96f, .08f)); add(k("Alt", "Alt", .21f, .96f, .08f))
        add(k("Delete", "Del", .90f, .30f, .06f)); add(k("Home", "Home", .90f, .48f, .06f))
        add(k("up", "↑", .90f, .76f, .05f)); add(k("left", "←", .84f, .94f, .05f))
        add(k("down", "↓", .90f, .94f, .05f)); add(k("right", "→", .96f, .94f, .05f))
    }
    // Reserve a small lower margin for the full space bar instead of placing its center at
    // the old body's bottom edge. Its own coordinate system includes this margin.
    return InputArtwork("Tastatur", InputArtworkShape.KEYBOARD,
        keys.map { it.copy(y = .07f + it.y * .84f) }, aspectRatio = 3.2f)
}

private fun phoneControllerArtwork(): InputArtwork {
    val keys = listOf(
        InputArtworkKey("up", "▲", .18f, .32f, RetroPadButton.UP),
        InputArtworkKey("down", "▼", .18f, .62f, RetroPadButton.DOWN),
        InputArtworkKey("left", "◀", .11f, .47f, RetroPadButton.LEFT),
        InputArtworkKey("right", "▶", .25f, .47f, RetroPadButton.RIGHT),
        InputArtworkKey("y", "Y", .74f, .47f, RetroPadButton.Y),
        InputArtworkKey("x", "X", .81f, .32f, RetroPadButton.X),
        InputArtworkKey("b", "B", .81f, .62f, RetroPadButton.B),
        InputArtworkKey("a", "A", .88f, .47f, RetroPadButton.A),
        InputArtworkKey("select", "−", .44f, .66f, RetroPadButton.SELECT),
        InputArtworkKey("start", "+", .56f, .66f, RetroPadButton.START),
        InputArtworkKey("l3", "L3", .34f, .76f, RetroPadButton.L3),
        InputArtworkKey("r3", "R3", .66f, .76f, RetroPadButton.R3),
    ) + listOf(RetroPadButton.L, RetroPadButton.R, RetroPadButton.L2, RetroPadButton.R2).map {
        InputArtworkKey(it.key, it.key.uppercase(Locale.ROOT), .5f, .5f, it, auxiliary = true)
    }
    return InputArtwork("Handy-Controller", InputArtworkShape.SCREEN_PAD, keys)
}
