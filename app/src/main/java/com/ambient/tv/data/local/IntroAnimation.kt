package com.ambient.tv.data.local

/**
 * Cold-start intro drawn in the theme accents; [OFF] hands over straight from the system splash.
 * Each style is one looping motion of the mark; [durationMs] is the length of one loop.
 */
object IntroAnimation {
    const val OFF = "off"
    const val POWER_ON = "power_on"
    const val BLOOM = "bloom"
    const val SIGNAL = "signal"
    const val SHIMMER = "shimmer"
    const val RIPPLE = "ripple"
    const val DEFAULT = BLOOM

    val keys = listOf(OFF, POWER_ON, BLOOM, SIGNAL, SHIMMER, RIPPLE)

    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: DEFAULT

    fun durationMs(key: String): Int = when (normalize(key)) {
        POWER_ON -> 2200
        SIGNAL -> 2000
        SHIMMER -> 2400
        RIPPLE -> 2000
        OFF -> 0
        else -> 3000
    }
}
