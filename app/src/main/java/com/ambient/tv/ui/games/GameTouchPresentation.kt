package com.ambient.tv.ui.games

import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.settings.consoleArtwork
import com.ambient.tv.ui.settings.consoleArtworkVariants

internal enum class GameTouchFaceShape { DIAMOND, PAIR, ROW, SIX, SINGLE }

/** One presentation contract for the editor, the playing phone and the phone controlling a TV. */
internal data class GameTouchPresentation(
    val labels: Map<RetroPadButton, String>,
    val faces: List<RetroPadButton>,
    val shape: GameTouchFaceShape,
) {
    companion object {
        val Generic = GameTouchPresentation(
            RetroPadButton.entries.associateWith { when (it) {
                RetroPadButton.L -> "L1"
                RetroPadButton.R -> "R1"
                else -> it.key.uppercase(java.util.Locale.ROOT)
            } },
            listOf(RetroPadButton.X, RetroPadButton.Y, RetroPadButton.A, RetroPadButton.B),
            GameTouchFaceShape.DIAMOND,
        )
    }
}

internal fun gameTouchPresentation(systemId: String?, variant: String = ""): GameTouchPresentation {
    if (systemId.isNullOrBlank() || variant == "retropad") return GameTouchPresentation.Generic
    val variants = consoleArtworkVariants(systemId)
    val selected = variants.firstOrNull { it.first == variant }?.first ?: variants.firstOrNull()?.first.orEmpty()
    val labels = consoleArtwork(systemId, selected).keys.mapNotNull { key ->
        key.target?.takeIf { key.label.isNotBlank() }?.let { it to key.label }
    }.toMap().toMutableMap()
    // Analog pad artwork does not draw the stick-click labels, but those buttons remain usable.
    if (systemId in setOf("psx", "ps2", "n3ds", "wiiu")) {
        labels[RetroPadButton.L3] = "L3"
        labels[RetroPadButton.R3] = "R3"
    }
    val six = systemId == "saturn" ||
        (systemId in setOf("megadrive", "sega32x") && selected == "md6") ||
        (systemId == "pce" && selected == "pce6")
    val faceOrder = if (six) listOf(RetroPadButton.L, RetroPadButton.X, RetroPadButton.R,
        RetroPadButton.Y, RetroPadButton.B, RetroPadButton.A)
    else listOf(RetroPadButton.Y, RetroPadButton.X, RetroPadButton.B, RetroPadButton.A)
    val faces = faceOrder.filter { it in labels }
    return GameTouchPresentation(labels, faces, when (faces.size) {
        1 -> GameTouchFaceShape.SINGLE
        2 -> GameTouchFaceShape.PAIR
        3 -> GameTouchFaceShape.ROW
        6 -> GameTouchFaceShape.SIX
        else -> GameTouchFaceShape.DIAMOND
    })
}
