package com.ambient.tv.data.local

data class HomeBackgroundSettings(
    val everywhere: Boolean = false,
    val focusArtwork: Boolean = true,
    val style: String = HomeHeroStyle.DEFAULT,
    val intensity: Int = HomeHeroStyle.DEFAULT_INTENSITY,
    val spread: String = HomeHeroStyle.DEFAULT_SPREAD,
) {
    fun visible(reducedEffects: Boolean, foregroundPlayback: Boolean, backgroundVideo: Boolean): Boolean =
        everywhere && !reducedEffects && !foregroundPlayback && !backgroundVideo &&
            intensity > 0 && style != HomeHeroStyle.STATIC
}
