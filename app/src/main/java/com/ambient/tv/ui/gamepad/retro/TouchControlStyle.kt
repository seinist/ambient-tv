package com.ambient.tv.ui.gamepad.retro

/** Small, portable UI preferences shared by local play and the phone controller. */
data class TouchControlStyle(
    val scalePercent: Int = 100,
    val opacityPercent: Int = 100,
    val portraitPosition: Int = 100,
    val landscapePosition: Int = 100,
    val stickDeadzonePercent: Int = 12,
    /** 0 = narrow diagonals, 1 = equal eight-way sectors, 2 = wide diagonals. */
    val diagonalSensitivity: Int = 1,
    val hideOnGamepadInput: Boolean = false,
    /** Light platform feedback for game button press edges, never for directions or stick motion. */
    val buttonHaptics: Boolean = false,
) {
    fun normalized() = copy(
        scalePercent = scalePercent.coerceIn(100, 140),
        opacityPercent = opacityPercent.coerceIn(25, 100),
        portraitPosition = portraitPosition.coerceIn(0, 100),
        landscapePosition = landscapePosition.coerceIn(0, 100),
        stickDeadzonePercent = stickDeadzonePercent.coerceIn(0, 35),
        diagonalSensitivity = diagonalSensitivity.coerceIn(0, 2),
    )

    /** The portable settings map keeps its legacy fields and stores haptics under a separate key. */
    fun encode(includeHaptics: Boolean = true): String = normalized().let {
        val legacy = listOf(it.scalePercent, it.opacityPercent, it.portraitPosition, it.landscapePosition,
            it.stickDeadzonePercent, it.diagonalSensitivity, if (it.hideOnGamepadInput) 1 else 0)
            .joinToString(",")
        if (includeHaptics) "$legacy,${if (it.buttonHaptics) 1 else 0}" else legacy
    }

    companion object {
        fun decode(raw: String?): TouchControlStyle {
            if (raw == null || raw.length > 64) return TouchControlStyle()
            val values = raw.split(',').map { it.toIntOrNull() ?: return TouchControlStyle() }
                .takeIf { it.size in 7..8 && it[6] in 0..1 && (it.size == 7 || it[7] in 0..1) }
                ?: return TouchControlStyle()
            return TouchControlStyle(values[0], values[1], values[2], values[3], values[4], values[5],
                values[6] == 1, buttonHaptics = values.getOrNull(7) == 1).normalized()
        }
    }
}
