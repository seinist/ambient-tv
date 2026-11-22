package com.ambient.tv.ui.settings

/**
 * Console keys keep their existing IDs, labels and RetroPad targets. These maps only move
 * visible controls onto the corresponding Controllercons source geometry (64 x 64 units).
 * Rear controls, console switches and very small slots stay in the auxiliary control strip.
 */
internal fun InputArtwork.withConsoleVector(systemId: String, variant: String = ""): InputArtwork = when (systemId) {
    "nes" -> withControllerVector("nes", directionAnchors(11.96f, 33.95f, 3.7f, 3.5f) + mapOf(
        "b" to ControllerKeyAnchor(43.13f, 35.95f, 5.4f),
        "a" to ControllerKeyAnchor(53.13f, 35.95f, 5.4f),
        // Start/Select are thin 1.3-unit slots, unsuitable for their full text labels.
    ))
    "snes" -> withControllerVector("snes", directionAnchors(13.68f, 32.57f, 3.35f, 3.2f) + mapOf(
        "y" to ControllerKeyAnchor(43.63f, 32.47f, 5f),
        "x" to ControllerKeyAnchor(50.45f, 27.12f, 5f),
        "b" to ControllerKeyAnchor(50.52f, 37.77f, 5f),
        "a" to ControllerKeyAnchor(57.34f, 32.41f, 5f),
        "select" to ControllerKeyAnchor(25.94f, 34.61f, 4.5f, 3.9f),
        "start" to ControllerKeyAnchor(33.05f, 34.61f, 4.5f, 3.9f),
    ))
    "megadrive", "sega32x" -> when (variant) {
        // Upstream mega-drive.svg depicts the original THREE-button pad, not a six-button pad.
        "md3" -> withControllerVector("mega-drive", directionAnchors(14.25f, 30.83f, 3.55f, 3.3f) + mapOf(
            "y" to ControllerKeyAnchor(43.17f, 34.58f, 5.2f), // A
            "b" to ControllerKeyAnchor(49.65f, 31.06f, 5.2f), // B
            "a" to ControllerKeyAnchor(56.32f, 28.41f, 5.2f), // C
        ))
        "sms" -> withControllerVector("master-system", directionAnchors(16.91f, 31.97f, 5.5f, 4.6f) + mapOf(
            "b" to ControllerKeyAnchor(45.10f, 35.58f, 6f),
            "a" to ControllerKeyAnchor(55.84f, 35.58f, 6f),
        ))
        else -> this // Six-button and Game Gear artwork need their own compatible silhouettes.
    }
    "n64" -> withControllerVector("n64", directionAnchors(11f, 20.95f, 3f, 2.8f) + mapOf(
        "b" to ControllerKeyAnchor(44.48f, 22.35f, 5.2f),
        "a" to ControllerKeyAnchor(49.17f, 27.23f, 5.2f),
        "x" to ControllerKeyAnchor(53.98f, 14.36f, 4f),
        "y" to ControllerKeyAnchor(50.05f, 18.24f, 4f),
        "l" to ControllerKeyAnchor(12.11f, 8.21f, 7f, 3.7f),
        "r" to ControllerKeyAnchor(51.91f, 8.24f, 7f, 3.7f),
        // Z is under the centre grip; Start is a small dot on the face.
    ))
    "psx" -> withControllerVector("ps1", mapOf(
        "up" to ControllerKeyAnchor(13.61f, 23.35f, 3.2f, 3.5f),
        "down" to ControllerKeyAnchor(13.62f, 30.13f, 3.2f, 3.5f),
        "left" to ControllerKeyAnchor(10.23f, 26.74f, 3.5f, 3.2f),
        "right" to ControllerKeyAnchor(17f, 26.74f, 3.5f, 3.2f),
        "y" to ControllerKeyAnchor(44.77f, 26.68f, 4.6f),
        "x" to ControllerKeyAnchor(50.61f, 21.33f, 4.6f),
        "b" to ControllerKeyAnchor(50.61f, 32.02f, 4.6f),
        "a" to ControllerKeyAnchor(56.48f, 26.68f, 4.6f),
    ))
    "ps2" -> withControllerVector("ps2", mapOf(
        "up" to ControllerKeyAnchor(12.85f, 23.12f, 2.9f, 3.4f),
        "down" to ControllerKeyAnchor(12.85f, 29.30f, 2.9f, 3.4f),
        "left" to ControllerKeyAnchor(9.64f, 26.38f, 3.4f, 2.9f),
        "right" to ControllerKeyAnchor(16.07f, 26.38f, 3.4f, 2.9f),
        "y" to ControllerKeyAnchor(45.87f, 26.36f, 4.3f),
        "x" to ControllerKeyAnchor(50.79f, 21.41f, 4.3f),
        "b" to ControllerKeyAnchor(50.79f, 31.06f, 4.3f),
        "a" to ControllerKeyAnchor(55.51f, 26.36f, 4.3f),
    ))
    "gamecube" -> withControllerVector("gamecube", directionAnchors(21.82f, 38.40f, 2.3f, 2.2f) + mapOf(
        // The upper-left round control is the stick; the D-pad is below and to its right.
        "b" to ControllerKeyAnchor(43.98f, 27.86f, 4.7f),
        "a" to ControllerKeyAnchor(51.33f, 24.16f, 7.2f),
        "x" to ControllerKeyAnchor(58.14f, 22.62f, 4.3f, 6.7f),
        "y" to ControllerKeyAnchor(49.38f, 17.45f, 6.7f, 4.3f),
    ))
    "wii" -> if (variant == "nunchuk") this else withControllerVector(
        "wii", directionAnchors(32f, 10.48f, 2.25f, 2.4f) + mapOf(
            "a" to ControllerKeyAnchor(32f, 20f, 5f),
            "l" to ControllerKeyAnchor(32f, 47.18f, 3.4f), // 1
            "r" to ControllerKeyAnchor(32f, 52.88f, 3.4f), // 2
            // B is on the back; +/- are tiny front buttons. None are placed over the speaker.
        ),
    )
    "wiiu" -> withControllerVector("wii-u", directionAnchors(8.21f, 30.36f, 1.7f, 1.7f) + mapOf(
        "y" to ControllerKeyAnchor(53.08f, 30.37f, 2.3f),
        "x" to ControllerKeyAnchor(55.89f, 27.77f, 2.3f),
        "b" to ControllerKeyAnchor(55.89f, 32.92f, 2.3f),
        "a" to ControllerKeyAnchor(58.64f, 30.34f, 2.3f),
    ))
    "dreamcast" -> withControllerVector("dreamcast", directionAnchors(12.79f, 34.75f, 3.15f, 3f) + mapOf(
        // Keep every existing target/label, but put A/B/X/Y on their real Dreamcast positions.
        "a" to ControllerKeyAnchor(52.21f, 31.30f, 5f),
        "b" to ControllerKeyAnchor(57.70f, 25.96f, 5f),
        "x" to ControllerKeyAnchor(46.73f, 25.96f, 5f),
        "y" to ControllerKeyAnchor(52.21f, 20.63f, 5f),
    ))
    "saturn" -> withControllerVector("sega-saturn", directionAnchors(13.90f, 30.63f, 3.5f, 3f) + mapOf(
        "y" to ControllerKeyAnchor(44.12f, 37.56f, 6.2f), // A
        "b" to ControllerKeyAnchor(50.35f, 33.85f, 6.2f), // B
        "a" to ControllerKeyAnchor(57.43f, 31.90f, 6.2f), // C
        "l" to ControllerKeyAnchor(42.58f, 29.84f, 4.6f), // X
        "x" to ControllerKeyAnchor(47.74f, 26.59f, 4.6f), // Y
        "r" to ControllerKeyAnchor(53.37f, 25.15f, 4.6f), // Z
    ))
    "vb" -> withControllerVector("virtual-boy", directionAnchors(9.61f, 15.40f, 2.9f, 2.7f) + mapOf(
        "b" to ControllerKeyAnchor(40.41f, 21.50f, 4f),
        "a" to ControllerKeyAnchor(45.64f, 19.17f, 4f),
        "select" to ControllerKeyAnchor(18.44f, 19.16f, 4f),
        "start" to ControllerKeyAnchor(23.67f, 21.50f, 4f),
        // X/Y and L/R stay auxiliary: the right D-pad is not a pair of face buttons.
    ))
    "a2600" -> withControllerVector("atari-2600", directionAnchors(32.14f, 35.13f, 10f, 5.5f) + mapOf(
        "b" to ControllerKeyAnchor(11.71f, 10.89f, 10f),
        // Start/reset is a console switch, not a button on this joystick.
    ))
    else -> this
}
