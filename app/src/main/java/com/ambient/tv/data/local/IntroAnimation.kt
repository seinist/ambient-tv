package com.ambient.tv.data.local

/**
 * Cold-start intro drawn in the theme accents. [OFF] hands over straight from the system splash.
 * [durationMs] is the one-shot opening. The mark then holds and the light breathes until the
 * intro is dismissed. Sparsam draws the finished mark without motion.
 */
object IntroAnimation {
    const val OFF = "off"
    const val POWER_ON = "power_on"
    const val BLOOM = "bloom"
    const val SIGNAL = "signal"
    const val SHIMMER = "shimmer"
    const val RIPPLE = "ripple"
    const val WORD = "word"
    const val HORIZON = "horizon"
    const val DEFAULT = BLOOM

    val keys = listOf(OFF, POWER_ON, BLOOM, SIGNAL, SHIMMER, RIPPLE, WORD, HORIZON)

    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: DEFAULT

    fun durationMs(key: String): Int = when (normalize(key)) {
        POWER_ON -> 2600
        SIGNAL -> 3000
        SHIMMER -> 2600
        RIPPLE -> 2400
        WORD -> 3400
        HORIZON -> 3200
        OFF -> 0
        else -> 2800
    }
}
