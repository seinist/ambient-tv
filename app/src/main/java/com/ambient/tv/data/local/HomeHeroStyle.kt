package com.ambient.tv.data.local

/** Visual treatments for the Home hero. Sparsam is intentionally static; Auto keeps a selected
 * treatment on a weak device, but runs its least expensive motion variant. */
object HomeHeroStyle {
    const val STATIC = "static"
    const val SWEEP = "sweep"
    const val ARTWORK_COLOR = "artwork_color"
    const val DEPTH = "depth"
    const val AURORA = "aurora"
    const val CINEMA = "cinema"
    const val PRISM = "prism"
    const val DUSK = "dusk"
    const val EMBER = "ember"
    const val TIDE = "tide"
    const val FOCUS = "focus"
    const val DEFAULT = DEPTH
    const val DEFAULT_INTENSITY = 60
    const val SOFT = "soft"
    const val FOCUSED = "focused"
    const val WIDE = "wide"
    const val DEFAULT_SPREAD = SOFT
    val spreads = listOf(SOFT, FOCUSED, WIDE)

    fun normalizeIntensity(value: Int): Int = value.coerceIn(0, 100)
    fun normalizeSpread(value: String?): String = value?.takeIf { it in spreads } ?: DEFAULT_SPREAD
    fun intensityScale(value: Int): Float = normalizeIntensity(value) / DEFAULT_INTENSITY.toFloat()
    fun radiusScale(spread: String?): Float = when (normalizeSpread(spread)) {
        FOCUSED -> 0.72f
        WIDE -> 1.32f
        else -> 1f
    }

    val keys = setOf(STATIC, SWEEP, ARTWORK_COLOR, DEPTH, AURORA, CINEMA, PRISM, DUSK, EMBER, TIDE, FOCUS)

    private val fancy = setOf(AURORA, CINEMA, PRISM, DUSK, EMBER, TIDE, FOCUS)

    fun isFancy(key: String) = key in fancy
    fun isAnimated(key: String) = key == SWEEP || key == DEPTH || isFancy(key)
    fun samplesArtwork(key: String) = key == ARTWORK_COLOR || isFancy(key)

    /** One-way length of the ambient loop. The clock eases out and back, so a full cycle is twice this. */
    fun ambientPeriodMs(key: String): Int = when (normalize(key)) {
        SWEEP -> 26_000
        DEPTH -> 24_000
        AURORA -> 32_000
        CINEMA -> 30_000
        PRISM -> 28_000
        DUSK -> 36_000
        EMBER -> 34_000
        TIDE -> 30_000
        FOCUS -> 26_000
        else -> 24_000
    }

    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: DEFAULT

    /** Low mode and reduced effects (Auto on a weak device) both show the static hero. */
    fun effective(key: String?, performanceModeKey: String?, reducedEffects: Boolean): String =
        if (performanceModeKey == "low" || reducedEffects) STATIC else normalize(key)

    /** Motion scale for animated styles; reduced effects never reach it via [effective]. */
    fun motionStrength(performanceModeKey: String?, reducedEffects: Boolean): Float = when {
        performanceModeKey == "low" -> 0f
        reducedEffects -> 0.42f
        performanceModeKey == "maximum" -> 1f
        else -> 0.72f
    }
}
